;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.workspace.table
  "Context-menu actions that insert or delete a row / a column in the flex
   based Table component (Table -> 单列 columns -> 单元格 cells).

   A shape is considered part of such a table when the instance root that
   contains it has its :component-id configured in `app.config/table-component-ids`
   (populated from `penpotTableComponentIds` in resources/config.js).

   Deletes reuse the native deletion of shapes inside a component copy: the
   cells / column are marked :hidden instead of removed, so they stay in the
   :shapes vectors (keeping the index alignment with the library) and a single
   undo restores them."
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.comments :as dc]
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

(defn- find-cell
  "Direct child of `column` that contains `shape` (or `shape` itself when it
   is a cell). nil when `shape` is the column, the table root or lives
   outside of them."
  [objects column shape]
  (loop [shape shape]
    (cond
      (nil? shape)
      nil

      (= (:id column) (:id shape))
      nil

      (= (:id column) (:parent-id shape))
      shape

      :else
      (recur (get objects (:parent-id shape))))))

(defn- visible-children
  "Immediate children of the shape `id` without the :hidden ones. A deleted
   table cell / column stays in the parent :shapes vector with :hidden true
   and its stale pre-deletion geometry, and the layout ignores it, so every
   geometry derivation (row matching, insertion indexes, clone sources)
   must filter through this. Structure validation deliberately does NOT:
   a hidden column is still a structurally valid table column."
  [objects id]
  (remove :hidden (cfh/get-immediate-children objects id)))

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
  "Index in the column :shapes vector where the new row must be added: the
   slot adjacent to `anchor` (the clone source: the clicked row's cell, or
   the visually bottom-most visible cell on the bottom-append fallback) on
   its real position on the parent. A deleted cell stays in :shapes with
   its stale geometry, so counting the visible children could place the
   new row in the visual middle of the column. When the :shapes order
   runs like the visual one (:inc) the clone goes right after the anchor
   slot; when it runs bottom->top (:dec) it takes the anchor slot itself,
   pushing the anchor up. Caveat: with fewer than two visible children
   the order sign is unknown and the :inc default is used, which on a
   :dec column reduced to a single visible cell inserts after it instead
   of at index 0."
  [objects children anchor]
  (let [slot (cfh/get-position-on-parent objects (:id anchor))]
    (if (= :dec (order-sign children :y))
      slot
      (inc slot))))

(defn- row-rank
  "Index of `cell` among `children` (its visible siblings) when they are
   sorted by :y ascending: the visual row it sits in."
  [children cell]
  (let [sorted-ids (mapv :id (sort-by :y children))]
    (.indexOf sorted-ids (:id cell))))

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
  "Inserts a row below the one the cell `shape-id` sits in: every visible
   column gets a clone of its own visible cell at the clicked cell's row
   rank (its visible siblings sorted by :y ascending, the same rank
   matching as the row delete) and the table root grows by the row height
   (the max height of the cloned cells). From the table root or a column
   frame itself — no cell to resolve — it falls back to appending a row at
   the visual bottom: every column clones its own visually bottom-most
   visible cell. Refused when no visible column is left, some visible
   column has no visible cell left (the native delete-inside-instance can
   hide them all), or — for a clicked cell — some visible column has no
   visible cell at that row rank: the clicked cell itself hidden (a hidden
   cell no longer has a rank among its visible siblings) or a rank beyond
   the visible count of a shorter column.

   Caveat: like `row-targets`, the rank matching relies on the stored
   geometry of the cells, so when the visible cell counts of the columns
   disagree (e.g. after rows were deleted), which cell of a column
   corresponds to a rank of another column is an heuristic."
  [shape-id]
  (ptk/reify ::insert-table-row
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (dsh/lookup-page-objects state page-id)
            file      (dsh/lookup-file state)
            libraries (dsh/lookup-libraries state)
            shape     (get objects shape-id)
            root      (find-table-root objects shape)
            column    (when root (find-column objects root shape))
            cell      (when column (find-cell objects column shape))
            rank      (when cell (row-rank (visible-children objects (:parent-id cell)) cell))
            columns   (when root (visible-children objects (:id root)))]
        (if (or (nil? root)
                (empty? columns)
                ;; a visible column with every cell hidden has no clone
                ;; source for the new row; on the fallback path below it
                ;; would also make bottom-cell throw on empty children
                (some #(empty? (visible-children objects (:id %))) columns))
          (rx/empty)
          (let [;; per column, the cell its new row is cloned from: its own
                ;; visible cell at the clicked row rank (`get` on the sorted
                ;; vector answers nil beyond the visible count and for the
                ;; -1 of a hidden clicked cell; the `vec` is load-bearing:
                ;; `get` on the seq `sort-by` returns would answer nil
                ;; unconditionally, silently turning every clicked-cell
                ;; insert into a no-op), or its visually bottom-most visible cell
                ;; when no cell was resolved (root / column dispatch, rank
                ;; nil)
                sources
                (mapv (fn [col]
                        (let [children (visible-children objects (:id col))]
                          (if (nil? rank)
                            (bottom-cell children)
                            (get (vec (sort-by :y children)) rank))))
                      columns)]
            ;; some visible column has no visible cell at the clicked rank
            ;; (hidden clicked cell or a shorter column): no clone source,
            ;; refuse
            (if (some nil? sources)
              (rx/empty)
              (let [clones
                    (mapv (fn [col src-cell]
                            (let [children  (visible-children objects (:id col))
                                  index     (row-insert-index objects children src-cell)
                                  new-id    (uuid/next)
                                  head-slot (swap-slot-for-clone file libraries objects col src-cell new-id index)
                                  clone     (clone-subtree src-cell (:id col) objects new-id head-slot)
                                  slots     (swap-slot-fixes file libraries objects col new-id index)]
                              {:index index
                               :clone clone
                               :slots slots}))
                          columns sources)

                    row-height (transduce (map :height) max 0 sources)

                    undo-id (uuid/next)

                    ;; NB: resize the root lazily, from the shape as it exists at
                    ;; this point of the changes (i.e. already including the new
                    ;; cells). Handing update-shapes a full precomputed shape would
                    ;; diff :shapes against the pre-insert value and revert the
                    ;; inserts done by the add-obj changes above.
                    grow-root (fn [shape]
                                (gsh/transform-shape
                                 shape
                                 (ctm/change-size shape nil (+ (:height shape) row-height))))

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
                       (dwu/commit-undo-transaction undo-id))))))))))

(defn insert-table-column
  "Inserts a column to the right of the column that contains `shape-id`
   (cloned from it; from the table root itself, cloned from the right-most
   column), and grows the table root by the column width. Refused when no
   visible column is left (the native delete-inside-instance can hide them
   all)."
  [shape-id]
  (ptk/reify ::insert-table-column
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id   (:current-page-id state)
            objects   (dsh/lookup-page-objects state page-id)
            file      (dsh/lookup-file state)
            libraries (dsh/lookup-libraries state)
            root      (find-table-root objects (get objects shape-id))
            columns   (when root (visible-children objects (:id root)))]
        (if (or (nil? root)
                ;; no visible column left: nothing to derive the right-most
                ;; column (the clone source) from
                (empty? columns))
          (rx/empty)
          (let [target     (or (find-column objects root (get objects shape-id))
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

(defn- row-targets
  "Cells to hide for deleting the row `cell` sits in: for every visible
   column of `root`, the visible cell at the same row rank, plus the height
   to shrink the root by (the max height of the targets, mirroring how the
   insert grows it). nil when the delete must not run: some visible column
   would keep a single visible cell (the last row of a column is protected),
   or has no visible cell at that rank.

   Caveat: the rank matching relies on the stored geometry of the cells, so
   when the visible cell counts of the columns disagree (e.g. after rows
   were deleted), which cell of a column corresponds to a rank of another
   column is an heuristic."
  [objects root cell]
  (let [rank    (row-rank (visible-children objects (:parent-id cell)) cell)
        columns (mapv #(vec (sort-by :y (visible-children objects (:id %))))
                      (visible-children objects (:id root)))
        targets (mapv #(get % rank) columns)]
    (when (and (every? #(>= (count %) 2) columns)
               (every? some? targets))
      {:cells targets
       :row-height (transduce (map :height) max 0 targets)})))

(defn can-delete-table-row?
  "Whether the table row of `shape` can be deleted: `shape` resolves to a
   cell of a configured table and every visible column has a visible cell
   at its row rank. Meant for greying out the context menu entry; the event
   rechecks the whole chain."
  [objects shape]
  (let [root (find-table-root objects shape)]
    (boolean
     (and (some? root)
          (when-let [column (find-column objects root shape)]
            (when-let [cell (find-cell objects column shape)]
              (row-targets objects root cell)))))))

(defn can-delete-table-column?
  "Whether the table column of `shape` can be deleted: `shape` is inside a
   configured table and at least one other visible column would remain."
  [objects shape]
  (let [root (find-table-root objects shape)]
    (boolean
     (and (some? root)
          (some? (find-column objects root shape))
          (>= (count (visible-children objects (:id root))) 2)))))

(defn delete-table-row
  "Hides the cell at the row of `shape-id` in every visible column of the
   table that contains it (the native delete-inside-instance mechanism, so
   the cells stay in the :shapes vectors and keep their index alignment
   with the library) and shrinks the table root by the row height."
  [shape-id]
  (ptk/reify ::delete-table-row
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            file-id (:current-file-id state)
            fdata   (dsh/lookup-file-data state file-id)
            page    (dsh/get-page fdata page-id)
            shape   (get objects shape-id)
            root    (find-table-root objects shape)
            column  (when root (find-column objects root shape))
            cell    (when column (find-cell objects column shape))
            targets (when cell (row-targets objects root cell))]
        (if (nil? targets)
          (rx/empty)
          (let [{:keys [cells row-height]} targets
                ids     (set (map :id cells))
                ;; uuid and not js/Symbol: it doubles as the changes
                ;; :undo-group, that the append-undo schema checks as uuid.
                undo-id (uuid/next)

                ;; NB: resize the root lazily, from the shape as it exists
                ;; at this point of the changes. Handing update-shapes a
                ;; full precomputed shape would diff :shapes against the
                ;; pre-delete value and revert the changes built above. The
                ;; min of 1 keeps the size positive even for a row taller
                ;; than the root.
                shrink-root (fn [shape]
                              (gsh/transform-shape
                               shape
                               (ctm/change-size shape nil (max 1 (- (:height shape) row-height)))))

                [all-parents changes]
                (cls/generate-delete-shapes (pcb/empty-changes it page-id)
                                            fdata page objects ids {})

                changes (-> changes
                            (pcb/update-shapes [(:id root)] shrink-root)
                            (pcb/set-undo-group undo-id))]

            ;; the selection moves to the root: the hidden cells would
            ;; otherwise linger in it (the selection update does not
            ;; filter hidden shapes out).
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dc/detach-comment-thread ids)
                   (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set (:id root)))
                   (ptk/data-event :layout/update
                                   {:ids all-parents
                                    :undo-group undo-id})
                   (dwu/commit-undo-transaction undo-id))))))))

(defn delete-table-column
  "Hides the column that contains `shape-id` (the native
   delete-inside-instance mechanism, so the column stays in the root
   :shapes vector) and shrinks the table root by the column width. Refused
   when `shape-id` does not resolve inside a column (e.g. from the table
   root itself) or it is the last visible column."
  [shape-id]
  (ptk/reify ::delete-table-column
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (dsh/lookup-page-objects state page-id)
            file-id (:current-file-id state)
            fdata   (dsh/lookup-file-data state file-id)
            page    (dsh/get-page fdata page-id)
            shape   (get objects shape-id)
            root    (find-table-root objects shape)
            column  (when root (find-column objects root shape))]
        (if (or (nil? column)
                (< (count (visible-children objects (:id root))) 2))
          (rx/empty)
          (let [ids     #{(:id column)}
                ;; uuid and not js/Symbol: it doubles as the changes
                ;; :undo-group, that the append-undo schema checks as uuid.
                undo-id (uuid/next)

                ;; NB: resize the root lazily, from the shape as it exists
                ;; at this point of the changes, so the diff does not
                ;; revert the :shapes vector of the root. The min of 1
                ;; keeps the size positive even for a column wider than
                ;; the root.
                shrink-root (fn [shape]
                              (gsh/transform-shape
                               shape
                               (ctm/change-size shape (max 1 (- (:width shape) (:width column))) nil)))

                [all-parents changes]
                (cls/generate-delete-shapes (pcb/empty-changes it page-id)
                                            fdata page objects ids {})

                changes (-> changes
                            (pcb/update-shapes [(:id root)] shrink-root)
                            (pcb/set-undo-group undo-id))]

            ;; the selection moves to the root: the hidden column would
            ;; otherwise linger in it (the selection update does not
            ;; filter hidden shapes out).
            (rx/of (dwu/start-undo-transaction undo-id)
                   (dc/detach-comment-thread ids)
                   (dch/commit-changes changes)
                   (dws/select-shapes (d/ordered-set (:id root)))
                   (ptk/data-event :layout/update
                                   {:ids all-parents
                                    :undo-group undo-id})
                   (dwu/commit-undo-transaction undo-id))))))))
