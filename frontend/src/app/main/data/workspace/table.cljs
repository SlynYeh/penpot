;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.table
  "Context-menu actions that insert a row or a column into the flex based
   Table component (Table -> 单列 columns -> 单元格 cells).

   A shape is considered part of such a table when the instance root that
   contains it has its :component-id configured in `app.config/table-component-ids`
   (populated from `penpotTableComponentIds` in resources/config.js)."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn find-table-root
  "Returns the table instance root for `shape` (or `shape` itself when it is
   the root), when its component is configured as a table in
   `cf/table-component-ids` and its structure looks like a flex table."
  [objects shape]
  (let [root (ctn/get-instance-root objects shape)]
    (when (and (some? root)
               (contains? cf/table-component-ids (str (:component-id root)))
               (= :flex (:layout root))
               (d/not-empty? (:shapes root))
               (every? (fn [column]
                         (and (cfh/frame-shape? column)
                              (d/not-empty? (:shapes column))))
                       (cfh/get-immediate-children objects (:id root))))
      root)))

(defn- find-column
  "Direct child of `root` that contains `shape` (or `shape` itself when it
   is a column). nil when `shape` is the root or lives outside of it."
  [objects root shape]
  (loop [shape shape]
    (cond
      (nil? shape)
      nil

      (= (:id root) (:id shape))
      nil

      (= (:id root) (:parent-id shape))
      shape

      :else
      (recur (get objects (:parent-id shape))))))

(defn- max-by
  [f coll]
  (reduce (fn [acc shape]
            (if (> (f shape) (f acc)) shape acc))
          coll))

(defn- bottom-cell
  "Visually bottom-most child of a column (by stored geometry)."
  [children]
  (max-by (fn [shape] (+ (or (:y shape) 0) (or (:height shape) 0))) children))

(defn- rightmost-column
  "Visually right-most column (by stored geometry)."
  [columns]
  (max-by :x columns))

(defn- order-sign
  "Whether the `:shapes` vector of a container runs in the same direction as
   the visual `axis` (:x or :y), inferred from the stored geometry of its
   first two children.

   Needed because the real Table component mixes :column / :column-reverse
   columns (and a :row-reverse root), so the :shapes order relative to the
   visual order is not uniform. Caveat: only the first two children are
   inspected, so an absolutely positioned or hidden head could mislead it."
  [children axis]
  (when (>= (count children) 2)
    (let [a (axis (first children))
          b (axis (second children))]
      (cond
        (< a b) :inc
        (> a b) :dec
        :else nil))))

(defn- row-insert-index
  "Index in the column :shapes vector where a new bottom row must be added."
  [children]
  (if (= :dec (order-sign children :y))
    0
    (count children)))

(defn- column-insert-index
  "Index in the root :shapes vector where a column cloned from `target`
   must be added so it renders immediately to its right."
  [objects columns target]
  (let [index (cfh/get-position-on-parent objects (:id target))]
    (if (= :dec (order-sign columns :x))
      index
      (inc index))))

(defn- apply-swap-slot
  "Return `shape` with its swap-slot touched group replaced by `slot` (nil
   clears it), keeping every other touched group. A slot already present is
   removed first: `get-swap-slot` reads the first swap-slot group it finds,
   so `set-swap-slot` on an already slotted shape would leave two live
   groups."
  [shape slot]
  (let [shape (cond-> shape
                (ctk/get-swap-slot shape)
                ctk/remove-swap-slot)]
    (cond-> shape
      (some? slot)
      (ctk/set-swap-slot slot))))

(defn- post-insert-container
  "A page-like container where the insertion of `insert-id` at `index` among
   the children of `parent` is already reflected in the parent :shapes, so
   `ctf/find-near-match` resolves every child to the position it will occupy
   once the change is applied (the validator runs the same lookup on the
   post-change file)."
  [objects parent insert-id index]
  (let [post-ids (vec (concat (take index (:shapes parent))
                              [insert-id]
                              (drop index (:shapes parent))))]
    {:objects (assoc objects (:id parent) (assoc parent :shapes post-ids))}))

(defn- slot-of-near-match
  "Slot value expected for a child whose near match is `near-match`: the
   library shape's own slot when it is itself swapped, its id otherwise."
  [near-match]
  (or (ctk/get-swap-slot near-match) (:id near-match)))

(defn- swap-slot-for-clone
  "Swap slot a clone of `source` must carry once inserted with `clone-id` at
   `index` among the children of `parent`: the slot of the library shape
   occupying that position, when the :shape-ref inherited from the source
   points somewhere else. nil when no slot is needed (the clone lands on the
   library shape its ref points at, or beyond the library children)."
  [file libraries objects parent source clone-id index]
  (let [near-match (ctf/find-near-match
                    file
                    (post-insert-container objects parent clone-id index)
                    libraries
                    ;; find-near-match only reads :parent-id and :id from the
                    ;; child (its position in the parent), so the source with
                    ;; the clone id stands in for the future clone.
                    (assoc source :id clone-id)
                    :include-deleted? true)]
    (when (and (some? near-match)
               (not= (:shape-ref source) (:id near-match)))
      (slot-of-near-match near-match))))

(defn- swap-slot-fixes
  "Swap-slot updates {shape-id slot} needed on the nested component copies
   among the CURRENT children of `parent` when `insert-id` is inserted at
   `index`: the insert shifts the children after that position, so every
   shifted copy no longer sits on the library shape its :shape-ref points at
   and, like the :missing-slot repair establishes, must carry the slot of
   the library shape that now occupies its position. A slot that went stale
   (child beyond the last library child, where it could collide with a
   sibling's slot) is cleared (nil value). Children aligned with their
   library counterpart are left untouched: they may legitimately keep an
   inherited slot."
  [file libraries objects parent insert-id index]
  (let [container (post-insert-container objects parent insert-id index)]
    (into {}
          (keep (fn [child-id]
                  (let [child (get objects child-id)]
                    (when (and (ctk/subcopy-head? child)
                               (not= child-id insert-id))
                      (let [near-match (ctf/find-near-match file container libraries child
                                                            :include-deleted? true)]
                        (cond
                          (nil? near-match)
                          (when (ctk/get-swap-slot child)
                            [child-id nil])

                          (= (:shape-ref child) (:id near-match))
                          nil

                          :else
                          [child-id (slot-of-near-match near-match)]))))))
          (:shapes parent))))

(defn- clone-subtree
  "Clone `shape` and its children under `parent-id`, giving the clone root
   `force-id`. New ids are generated for the descendants, :parent-id/:frame-id
   links are fixed and the component linkage (:component-id/:shape-ref) is
   preserved verbatim: the clone is a new sibling instance of the same
   nested component. `head-slot` becomes the swap slot of the clone root
   (nil strips any slot inherited from the source), mirroring how the native
   duplicate-inside-instance resets the swap slots of the duplicated head.

   Returns [cloned-root new-shapes]."
  [shape parent-id objects force-id head-slot]
  (let [[head new-shapes] (ctst/clone-shape shape parent-id objects :force-id force-id)
        head' (apply-swap-slot head head-slot)]
    [head' (assoc new-shapes 0 head')]))

(defn insert-table-row
  "Appends a row at the bottom of the table that contains `shape-id`: every
   column gets a clone of its own visually bottom-most cell, and the table
   root grows by the row height."
  [shape-id]
  (ptk/reify ::insert-table-row
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (dsh/lookup-page-objects state page-id)
            file      (dsh/lookup-file state)
            libraries (dsh/lookup-libraries state)
            root      (find-table-root objects (get objects shape-id))]
        (if (nil? root)
          (rx/empty)
          (let [columns (cfh/get-immediate-children objects (:id root))

                clones
                (mapv (fn [column]
                        (let [children  (cfh/get-immediate-children objects (:id column))
                              src-cell  (bottom-cell children)
                              index     (row-insert-index children)
                              new-id    (uuid/next)
                              head-slot (swap-slot-for-clone file libraries objects column src-cell new-id index)
                              clone     (clone-subtree src-cell (:id column) objects new-id head-slot)
                              slots     (swap-slot-fixes file libraries objects column new-id index)]
                          {:index index
                           :clone clone
                           :slots slots}))
                      columns)

                cell-height
                (transduce (comp (map #(cfh/get-immediate-children objects (:id %)))
                                 (map bottom-cell)
                                 (map :height))
                           max 0 columns)

                undo-id (uuid/next)

                ;; NB: resize the root lazily, from the shape as it exists at
                ;; this point of the changes (i.e. already including the new
                ;; cells). Handing update-shapes a full precomputed shape would
                ;; diff :shapes against the pre-insert value and revert the
                ;; inserts done by the add-obj changes above.
                grow-root (fn [shape]
                            (gsh/transform-shape
                             shape
                             (ctm/change-size shape nil (+ (:height shape) cell-height))))

                changes (as-> (pcb/empty-changes it page-id) $
                          (pcb/with-objects $ objects)
                          (reduce (fn [$ {:keys [index clone slots]}]
                                    (let [[head new-shapes] clone]
                                      (-> $
                                          (pcb/add-object head {:index index})
                                          (pcb/add-objects new-shapes)
                                          ;; cells shifted past their library
                                          ;; counterpart: point their swap slot
                                          ;; at the library shape that now
                                          ;; occupies their position.
                                          (cond-> (seq slots)
                                            (pcb/update-shapes (vec (keys slots))
                                                               #(apply-swap-slot % (get slots (:id %))))))))
                                  $ clones)
                          (pcb/update-shapes $ [(:id root)] grow-root)
                          (pcb/set-undo-group $ undo-id))]

            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (ptk/data-event :layout/update
                                   {:ids (into [(:id root)] (map :id) columns)
                                    :undo-group undo-id})
                   (dwu/commit-undo-transaction undo-id))))))))

(defn insert-table-column
  "Inserts a column to the right of the column that contains `shape-id`
   (cloned from it; from the table root itself, cloned from the right-most
   column), and grows the table root by the column width."
  [shape-id]
  (ptk/reify ::insert-table-column
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (dsh/lookup-page-objects state page-id)
            file      (dsh/lookup-file state)
            libraries (dsh/lookup-libraries state)
            root      (find-table-root objects (get objects shape-id))]
        (if (nil? root)
          (rx/empty)
          (let [columns    (cfh/get-immediate-children objects (:id root))
                target     (or (find-column objects root (get objects shape-id))
                               (rightmost-column columns))
                new-col-id (uuid/next)
                index      (column-insert-index objects columns target)
                head-slot  (swap-slot-for-clone file libraries objects root target new-col-id index)
                clone      (clone-subtree target (:id root) objects new-col-id head-slot)
                slots      (swap-slot-fixes file libraries objects root new-col-id index)
                new-shapes (second clone)
                undo-id    (uuid/next)

                ;; NB: resize lazily from the post-insert shape, so the diff
                ;; does not revert the :shapes vector of the root.
                grow-root (fn [shape]
                            (gsh/transform-shape
                             shape
                             (ctm/change-size shape
                                              (+ (:width shape) (:width target))
                                              nil)))

                changes (as-> (pcb/empty-changes it page-id) $
                          (pcb/with-objects $ objects)
                          (pcb/add-object $ (first new-shapes) {:index index})
                          (pcb/add-objects $ (rest new-shapes))
                          ;; columns shifted past their library counterpart
                          ;; (insert not at the end): give them the swap slot
                          ;; of the library column that now occupies their
                          ;; position.
                          (cond-> $ (seq slots)
                                  (pcb/update-shapes (vec (keys slots))
                                                     #(apply-swap-slot % (get slots (:id %)))))
                          (pcb/update-shapes $ [(:id root)] grow-root)
                          (pcb/set-undo-group $ undo-id))]

            (rx/of (dwu/start-undo-transaction undo-id)
                   (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set new-col-id))
                   (ptk/data-event :layout/update
                                   {:ids [(:id root)] :undo-group undo-id})
                   (dwu/commit-undo-transaction undo-id))))))))
