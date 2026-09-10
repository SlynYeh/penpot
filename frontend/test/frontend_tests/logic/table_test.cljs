;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.table-test
  (:require
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.component :as ctk]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.table :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

;; Fixture mirrors the structure of the real "Table 表格" component:
;;
;;   table-copy (instance of :table-comp, flex row-reverse, fixed size)
;;   ├─ copy-col-a (instance of :col-a-comp, flex column-reverse,
;;   │               :shapes runs top->bottom: head y0, cell1 y40, cell2 y80)
;;   │   ├─ copy-col-a-head   (instance of :cell-a-comp, y 0)
;;   │   ├─ copy-col-a-cell1  (instance of :cell-a-comp, y 40)
;;   │   └─ copy-col-a-cell2  (instance of :cell-a-comp, y 80 <- bottom)
;;   └─ copy-col-b (instance of :col-b-comp, flex column,
;;                   :shapes runs bottom->top: cell2 y80, cell1 y40, head y0)
;;       ├─ copy-col-b-cell2  (instance of :cell-b-comp, y 80 <- bottom)
;;       ├─ copy-col-b-cell1  (instance of :cell-b-comp, y 40)
;;       └─ copy-col-b-head   (instance of :cell-b-comp, y 0)
;;
;; The two cells components differ (:cell-a-comp / :cell-b-comp) so tests can
;; prove that each column clones its own cell at the clicked row rank (and its
;; own bottom cell for the append/fallback paths).

(def ^:private cell-w 100)
(def ^:private cell-h 40)
(def ^:private table-w 200)
(def ^:private table-h 160)

(defn- setup-file []
  (-> (cthf/sample-file :file1 :page-label :page-1)
      ;; cell mains (one inner rect child each)
      (ctho/add-frame :cell-a-frame {:name "cell-a" :width cell-w :height cell-h})
      (cths/add-sample-shape :cell-a-inner :parent-label :cell-a-frame {:type :rect :name "inner-a"})
      (cthc/make-component :cell-a-comp :cell-a-frame)

      (ctho/add-frame :cell-b-frame {:name "cell-b" :width cell-w :height cell-h})
      (cths/add-sample-shape :cell-b-inner :parent-label :cell-b-frame {:type :rect :name "inner-b"})
      (cthc/make-component :cell-b-comp :cell-b-frame)

      ;; column main A: :shapes order top->bottom (increasing y)
      (ctho/add-frame :col-a-frame {:name "col-a" :width cell-w :height table-h
                                    :layout :flex :layout-flex-dir :column-reverse})
      (cthc/instantiate-component :cell-a-comp :col-a-head :parent-label :col-a-frame)
      (cths/update-shape :col-a-head :y 0)
      (cthc/instantiate-component :cell-a-comp :col-a-cell1 :parent-label :col-a-frame)
      (cths/update-shape :col-a-cell1 :y 40)
      (cthc/instantiate-component :cell-a-comp :col-a-cell2 :parent-label :col-a-frame)
      (cths/update-shape :col-a-cell2 :y 80)
      (cthc/make-component :col-a-comp :col-a-frame)

      ;; column main B: :shapes order bottom->top (decreasing y)
      (ctho/add-frame :col-b-frame {:name "col-b" :width cell-w :height table-h
                                    :layout :flex :layout-flex-dir :column})
      (cthc/instantiate-component :cell-b-comp :col-b-cell2 :parent-label :col-b-frame)
      (cths/update-shape :col-b-cell2 :y 80)
      (cthc/instantiate-component :cell-b-comp :col-b-cell1 :parent-label :col-b-frame)
      (cths/update-shape :col-b-cell1 :y 40)
      (cthc/instantiate-component :cell-b-comp :col-b-head :parent-label :col-b-frame)
      (cths/update-shape :col-b-head :y 0)
      (cthc/make-component :col-b-comp :col-b-frame)

      ;; table main
      (ctho/add-frame :table-frame {:name "table" :width table-w :height table-h
                                    :layout :flex :layout-flex-dir :row-reverse
                                    :layout-item-h-sizing :fix})
      (cthc/instantiate-component :col-a-comp :table-col-a :parent-label :table-frame)
      (cths/update-shape :table-col-a :x 0)
      (cthc/instantiate-component :col-b-comp :table-col-b :parent-label :table-frame)
      (cths/update-shape :table-col-b :x 100)
      (cthc/make-component :table-comp :table-frame)

      ;; instance copy the menu actions operate on (children labels follow
      ;; the DFS :shapes order of the copied subtree)
      ;; NOTE: `children-labels` follows `cfh/get-children-ids` order, which is
      ;; level-wise, not pre-order DFS: [columns] then [col-a cells] [col-b cells]
      ;; then [col-a inner rects] [col-b inner rects].
      (cthc/instantiate-component :table-comp :table-copy :children-labels
                                  [:copy-col-a
                                   :copy-col-b
                                   :copy-col-a-head
                                   :copy-col-a-cell1
                                   :copy-col-a-cell2
                                   :copy-col-a-head-inner
                                   :copy-col-a-cell1-inner
                                   :copy-col-a-cell2-inner
                                   :copy-col-b-cell2
                                   :copy-col-b-cell1
                                   :copy-col-b-head
                                   :copy-col-b-cell2-inner
                                   :copy-col-b-cell1-inner
                                   :copy-col-b-head-inner])))

(defn- page-objects [file]
  (-> (cthf/current-page file) :objects))

(defn- get-shape' [file label]
  (let [objects (page-objects file)
        shape (cths/get-shape file label)]
    (or (get objects (:id shape)) shape)))

(defn- config-with-table-comp []
  #{(str (cthi/id :table-comp))})

(defn- capture-commit-fn
  "Wraps `dch/commit-changes` so every changes map committed during the test
   is recorded in `committed`. with-redefs on the var is visible to the event
   code because CLJS calls resolve through the namespace object property."
  [committed]
  (let [orig dch/commit-changes]
    (fn [changes]
      (vswap! committed conj changes)
      (orig changes))))

(defn- assert-undo-group-uuid!
  "Regression guard: the changes and undo-entry schemas type :undo-group as
   uuid. The sm/check assertions only fire in dev builds, so a js/Symbol
   undo id passes this test build silently and crashes the app at runtime
   (check error on [:undo-group ::sm/uuid] in append-undo)."
  [committed]
  (t/is (= 1 (count @committed)) "exactly one commit for the table action")
  (t/is (uuid? (:undo-group (first @committed))) ":undo-group must be a uuid"))

(defn- assert-valid-file!
  "The backend dev flags enable the same referential-integrity validation on
   every committed change, so the insert changes must keep the file valid.
   (Asserts on the returned errors instead of cthf/validate-file! because an
   exception thrown inside the run-store callback is swallowed by the async
   harness and never reported as a failure.)"
  [file]
  (let [errors (cfv/validate-file file {})]
    (t/is (nil? errors)
          (pr-str (mapv #(select-keys % [:code :shape-id :args]) errors)))))

(defn- wire-undo-stack!
  "The real app turns `dch/commit` events into undo-stack entries in an
   effect of `app.main.data.workspace/initialize-workspace`; the bare test
   store has no such bootstrap, so this mirrors that wiring (and nothing
   else) to make `dwu/undo` work."
  [store]
  (->> (ptk/input-stream store)
       (rx/filter dch/commit?)
       (rx/map deref)
       (rx/mapcat (fn [{:keys [save-undo? undo-changes redo-changes undo-group tags stack-undo? selected-before]}]
                    (if (and save-undo? (seq undo-changes))
                      (rx/of (dwu/append-undo {:undo-changes undo-changes
                                               :redo-changes redo-changes
                                               :undo-group undo-group
                                               :tags tags
                                               :selected-before selected-before}
                                              stack-undo?))
                      (rx/empty))))
       (rx/subs! (fn [event] (ptk/emit! store event)))))

(defn- order-signature
  "Fixture-independent, direction-sensitive summary of the resulting
   :shapes order of `label`: for each child its idmap label (::unregistered
   for the fresh ids of the inserted clones), its coordinate on `axis`
   (:y for a column, :x for the root — the clone keeps the geometry of its
   source, which no layout ever reflows in the test store) and whether it
   carries a swap slot. Canonicalizing by label and not by geometry is
   what lets the signature tell :above apart from :below (and :left from
   :right): both directions produce the same coordinates and only swap
   which positions the clone and the pushed cells occupy. Comparing the
   raw ids instead would never match: two separately built fixtures draw
   all their ids (and the library :shape-ref / slot values they point at)
   from a counter that never resets inside a test. The signature must be
   realized before a second setup-file re-registers the labels."
  [state label axis]
  (let [file'   (ths/get-file-from-state state)
        objects (page-objects file')]
    (mapv (fn [id]
            (let [child-label (cthi/label id)]
              [(if (string? child-label) ::unregistered child-label)
               (or (axis (get objects id)) 0)
               (boolean (ctk/get-swap-slot (get objects id)))]))
          (:shapes (get-shape' file' label)))))

(t/deftest insert-row-below-clicked-bottom-row
  (t/testing "clicking the bottom row inserts the clone right below it: each column gains a clone of its own bottom cell at the visual bottom, root grows by row height"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-cell2))
                events [(dwt/insert-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'     (ths/get-file-from-state new-state)
                                     _         (assert-valid-file! file')
                                     objects   (page-objects file')
                                     root      (get-shape' file' :table-copy)
                                     col-a     (get-shape' file' :copy-col-a)
                                     col-b     (get-shape' file' :copy-col-b)
                                     src-a     (get-shape' file' :copy-col-a-cell2)
                                     src-b     (get-shape' file' :copy-col-b-cell2)]

                                 (t/testing "column A (:shapes top->bottom): new cell right below the clicked row (at the END)"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (peek (:shapes col-a)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))
                                     (t/is (not= (:id src-a) (:id new-cell)))))

                                 (t/testing "column B (:shapes bottom->top): new cell right below the clicked row (at index 0)"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (first (:shapes col-b)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell)))))

                                 (t/testing "cloned subtree has new ids and keeps children"
                                   (let [new-cell (get objects (peek (:shapes col-a)))
                                         new-inner (get objects (first (:shapes new-cell)))]
                                     (t/is (some? new-inner))
                                     (t/is (= (:shape-ref (get-shape' file' :copy-col-a-cell2-inner))
                                              (:shape-ref new-inner)))
                                     (t/is (not= (:id (get-shape' file' :copy-col-a-cell2-inner))
                                                 (:id new-inner)))))

                                 (t/testing "swap slots keep the file valid for the referential-integrity checker"
                                   ;; col-b (:shapes bottom->top) got the clone at index 0, so
                                   ;; each existing cell moved one position: its swap slot must
                                   ;; point at the library cell that now occupies its position
                                   ;; (= the :shape-ref of the cell that previously sat there).
                                   (let [cell2 (get-shape' file' :copy-col-b-cell2)
                                         cell1 (get-shape' file' :copy-col-b-cell1)
                                         head  (get-shape' file' :copy-col-b-head)]
                                     (t/is (= (:shape-ref cell1) (ctk/get-swap-slot cell2)))
                                     (t/is (= (:shape-ref head) (ctk/get-swap-slot cell1)))
                                     (t/is (nil? (ctk/get-swap-slot head))
                                           "beyond the library children: no slot"))
                                   ;; col-a (:shapes top->bottom) appends at the end: nothing
                                   ;; shifts and the clone lands beyond the library children,
                                   ;; so no slots are needed anywhere in the column.
                                   (doseq [cell-id (:shapes col-a)]
                                     (t/is (nil? (ctk/get-swap-slot (get objects cell-id))))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-row-below-middle-row
  (t/testing "clicking a middle row clones, per column, its visible cell at the clicked row rank and inserts the new row right below it"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                ;; cell1 sits at visual row rank 1 (head y0, cell1 y40, cell2 y80)
                shape-id (:id (cths/get-shape file :copy-col-a-cell1))
                events [(dwt/insert-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'     (ths/get-file-from-state new-state)
                                     _         (assert-valid-file! file')
                                     objects   (page-objects file')
                                     root      (get-shape' file' :table-copy)
                                     col-a     (get-shape' file' :copy-col-a)
                                     col-b     (get-shape' file' :copy-col-b)
                                     src-a     (get-shape' file' :copy-col-a-cell1)
                                     src-b     (get-shape' file' :copy-col-b-cell1)]

                                 (t/testing "column A (:shapes top->bottom) becomes [head cell1 NEW cell2]"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 2))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))
                                     (t/is (not= (:id src-a) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-a-head) (nth (:shapes col-a) 0)))
                                   (t/is (= (cthi/id :copy-col-a-cell1) (nth (:shapes col-a) 1)))
                                   (t/is (= (cthi/id :copy-col-a-cell2) (nth (:shapes col-a) 3))))

                                 (t/testing "column B (:shapes bottom->top) becomes [cell2 NEW cell1 head]: each column clones its OWN rank-1 cell"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (nth (:shapes col-b) 1))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell))
                                           "cloned from the col-b cell1, not from the clicked col-a cell1")
                                     (t/is (not= (:id src-b) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-b-cell2) (nth (:shapes col-b) 0)))
                                   (t/is (= (cthi/id :copy-col-b-cell1) (nth (:shapes col-b) 2)))
                                   (t/is (= (cthi/id :copy-col-b-head) (nth (:shapes col-b) 3))))

                                 (t/testing "cloned subtree has new ids and keeps children"
                                   (let [new-cell (get objects (nth (:shapes col-a) 2))
                                         new-inner (get objects (first (:shapes new-cell)))]
                                     (t/is (some? new-inner))
                                     (t/is (= (:shape-ref (get-shape' file' :copy-col-a-cell1-inner))
                                              (:shape-ref new-inner)))
                                     (t/is (not= (:id (get-shape' file' :copy-col-a-cell1-inner))
                                                 (:id new-inner)))))

                                 (t/testing "swap slots keep the file valid for the referential-integrity checker"
                                   ;; col-a: the clone sits at the library position of cell2 while
                                   ;; its :shape-ref points at the library cell1, so it must carry
                                   ;; the slot of the library cell it displaces.
                                   (let [new-a (get objects (nth (:shapes col-a) 2))]
                                     (t/is (= (:shape-ref (get-shape' file' :copy-col-a-cell2))
                                              (ctk/get-swap-slot new-a))))
                                   ;; ...and the shifted cell2 falls beyond the library children,
                                   ;; while head and cell1 stay aligned: no slot anywhere else.
                                   (doseq [cell-id [(cthi/id :copy-col-a-head)
                                                    (cthi/id :copy-col-a-cell1)
                                                    (cthi/id :copy-col-a-cell2)]]
                                     (t/is (nil? (ctk/get-swap-slot (get objects cell-id)))))
                                   ;; col-b: the clone lands exactly on the library cell1 its
                                   ;; :shape-ref points at, so it needs no slot of its own.
                                   (t/is (nil? (ctk/get-swap-slot (get objects (nth (:shapes col-b) 1)))))
                                   ;; the cells shifted past their library counterpart must point
                                   ;; their swap slot at the library cell that now occupies their
                                   ;; position (= the :shape-ref of the cell that previously sat
                                   ;; there); the head falls beyond the library children.
                                   (let [cell1 (get-shape' file' :copy-col-b-cell1)
                                         head  (get-shape' file' :copy-col-b-head)]
                                     (t/is (= (:shape-ref head) (ctk/get-swap-slot cell1)))
                                     (t/is (nil? (ctk/get-swap-slot head))
                                           "beyond the library children: no slot"))
                                   (t/is (nil? (ctk/get-swap-slot (get-shape' file' :copy-col-b-cell2)))
                                         "aligned with its library counterpart: no slot"))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-row-below-head-row
  (t/testing "clicking the head row clones, per column, its own head cell and inserts the new row right below it"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-head))
                events [(dwt/insert-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     head-a  (get-shape' file' :copy-col-a-head)
                                     head-b  (get-shape' file' :copy-col-b-head)]

                                 (t/testing "column A (:shapes top->bottom) becomes [head NEW cell1 cell2]"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 1))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id head-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref head-a) (:shape-ref new-cell)))
                                     (t/is (not= (:id head-a) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-a-head) (nth (:shapes col-a) 0)))
                                   (t/is (= (cthi/id :copy-col-a-cell1) (nth (:shapes col-a) 2)))
                                   (t/is (= (cthi/id :copy-col-a-cell2) (nth (:shapes col-a) 3))))

                                 (t/testing "column B (:shapes bottom->top) becomes [cell2 cell1 NEW head]: cloned from the col-b head, not from col-a's"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (nth (:shapes col-b) 2))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id head-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref head-b) (:shape-ref new-cell)))
                                     (t/is (not= (:id head-b) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-b-cell2) (nth (:shapes col-b) 0)))
                                   (t/is (= (cthi/id :copy-col-b-cell1) (nth (:shapes col-b) 1)))
                                   (t/is (= (cthi/id :copy-col-b-head) (nth (:shapes col-b) 3))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-row-from-root-falls-back-to-bottom-append
  (t/testing "inserting from the table root itself (no clicked cell to resolve) keeps the bottom-append behavior"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                root-id (:id (cths/get-shape file :table-copy))
                events  [(dwt/insert-table-row root-id)]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     src-a   (get-shape' file' :copy-col-a-cell2)
                                     src-b   (get-shape' file' :copy-col-b-cell2)]

                                 (t/testing "column A (:shapes top->bottom): new cell appended at the END, cloned from the bottom cell"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (peek (:shapes col-a)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))))

                                 (t/testing "column B (:shapes bottom->top): new cell inserted at index 0, cloned from the bottom cell"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (first (:shapes col-b)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell)))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))))))))
        done))))

(t/deftest insert-row-from-column-falls-back-to-bottom-append
  (t/testing "inserting from a column frame itself (no clicked cell to resolve) keeps the bottom-append behavior"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                col-id  (:id (cths/get-shape file :copy-col-a))
                events  [(dwt/insert-table-row col-id)]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     src-a   (get-shape' file' :copy-col-a-cell2)
                                     src-b   (get-shape' file' :copy-col-b-cell2)]

                                 (t/testing "column A (:shapes top->bottom): new cell appended at the END, cloned from the bottom cell"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (peek (:shapes col-a)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))))

                                 (t/testing "column B (:shapes bottom->top): new cell inserted at index 0, cloned from the bottom cell"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (first (:shapes col-b)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell)))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))))))))
        done))))

(t/deftest insert-column-right-of-clicked-column
  (t/testing "a full clone of the clicked column is inserted to its right, root grows by column width"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-cell1))
                events [(dwt/insert-table-column shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)]

                                 (t/testing "root has 3 columns; new column right after col-a"
                                   (t/is (= 3 (count (:shapes root))))
                                   (let [idx-a  (.indexOf (:shapes root) (:id col-a))
                                         new-col (get objects (nth (:shapes root) (inc idx-a)))]
                                     (t/is (some? new-col))
                                     (t/is (not= (:id col-b) (:id new-col)) "col-b shifted, new column sits between")
                                     (t/is (= (:component-id col-a) (:component-id new-col)))
                                     ;; nested instance heads inside a copy keep :component-id
                                     ;; but only the top instance root carries :component-root
                                     (t/is (= (:component-root col-a) (:component-root new-col)))
                                     (t/testing "cloned column keeps its 3 cells with refs"
                                       (t/is (= 3 (count (:shapes new-col))))
                                       (t/is (= (mapv :shape-ref (map objects (:shapes col-a)))
                                                (mapv :shape-ref (map objects (:shapes new-col))))))))

                                 (t/testing "cloned column carries the swap slot of the library column it displaces"
                                   ;; the new column sits at the library position of col-b, so
                                   ;; its swap slot must point at col-b's library counterpart.
                                   (let [idx-a   (.indexOf (:shapes root) (:id col-a))
                                         new-col (get objects (nth (:shapes root) (inc idx-a)))]
                                     (t/is (= (:shape-ref col-b) (ctk/get-swap-slot new-col)))))

                                 (t/testing "root resize: width + column width, height untouched"
                                   (t/is (= (+ table-w cell-w) (:width root)))
                                   (t/is (= table-h (:height root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-column-from-root-falls-back-to-rightmost
  (t/testing "inserting from the table root itself clones the rightmost column"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                root-id (:id (cths/get-shape file :table-copy))
                events [(dwt/insert-table-column root-id)]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-b   (get-shape' file' :copy-col-b)
                                     new-col (get objects (peek (:shapes root)))]
                                 (t/is (= 3 (count (:shapes root))))
                                 (t/is (some? new-col))
                                 (t/is (= (:component-id col-b) (:component-id new-col))
                                       "cloned from the rightmost column (col-b)")))))))
        done))))

(t/deftest insert-outside-configured-table-is-noop
  (t/testing "shapes inside a non-configured component instance produce no changes"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                before  (count (page-objects file))
                ;; col-a main is an instance root of :col-a-comp (not configured)
                shape-id (:id (cths/get-shape file :col-a-cell1))
                events  [(dwt/insert-table-row shape-id)]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)]
                                 (t/is (= before (count (page-objects file'))))))))))
        done))))

(t/deftest insert-without-config-is-noop
  (t/testing "with an empty config the actions do nothing"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                before  (count (page-objects file))
                shape-id (:id (cths/get-shape file :copy-col-a-cell2))
                events  [(dwt/insert-table-row shape-id)]]
            ;; no with-redefs: cf/table-component-ids defaults to #{} in tests
            (ths/run-store store done' events
                           (fn [new-state]
                             (let [file' (ths/get-file-from-state new-state)]
                               (t/is (= before (count (page-objects file')))))))))
        done))))

(t/deftest insert-row-with-fully-hidden-column-is-noop
  (t/testing "a column whose cells were all hidden with the native delete (no minimum to keep) refuses the row insert instead of crashing on a missing clone source"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (-> (setup-file)
                              ;; every cell of col-b hidden while the column
                              ;; frame itself stays visible: what the native
                              ;; delete-inside-instance leaves behind
                              (cths/update-shape :copy-col-b-cell2 :hidden true)
                              (cths/update-shape :copy-col-b-cell1 :hidden true)
                              (cths/update-shape :copy-col-b-head :hidden true))
                store     (ths/setup-store file)
                shape-id  (:id (cths/get-shape file :copy-col-a-cell2))
                events    [(dwt/insert-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)
                                     col-a (get-shape' file' :copy-col-a)]
                                 (t/is (empty? @committed) "guard refuses before committing")
                                 (t/is (= table-h (:height root)))
                                 (t/is (= table-w (:width root)))
                                 (t/is (= 3 (count (:shapes col-a))) "col-a unchanged")))))))
        done))))

(t/deftest insert-column-without-visible-columns-is-noop
  (t/testing "with every column hidden with the native delete, inserting a column from the root is refused instead of crashing on a missing rightmost column"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (-> (setup-file)
                              (cths/update-shape :copy-col-a :hidden true)
                              (cths/update-shape :copy-col-b :hidden true))
                store     (ths/setup-store file)
                root-id   (:id (cths/get-shape file :table-copy))
                events    [(dwt/insert-table-column root-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)]
                                 (t/is (empty? @committed) "guard refuses before committing")
                                 (t/is (= table-h (:height root)))
                                 (t/is (= table-w (:width root)))
                                 (t/is (= 2 (count (:shapes root))) "columns stay hidden in :shapes")))))))
        done))))

(t/deftest delete-row-hides-matching-cell-per-column
  (t/testing "each column hides the cell at the clicked row rank (rank match, not :shapes index), root shrinks by the row height"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (setup-file)
                store     (ths/setup-store file)
                ;; top row (rank 0): col-b :shapes runs bottom->top, so its
                ;; index-0 child is cell2, not the head; hiding the head in
                ;; BOTH columns proves the match is by row rank (:y), not by
                ;; :shapes index
                shape-id  (:id (cths/get-shape file :copy-col-a-head))
                events    [(dwt/delete-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'  (ths/get-file-from-state new-state)
                                     _      (assert-valid-file! file')
                                     root   (get-shape' file' :table-copy)
                                     col-a  (get-shape' file :copy-col-a)
                                     col-b  (get-shape' file :copy-col-b)
                                     col-a' (get-shape' file' :copy-col-a)
                                     col-b' (get-shape' file' :copy-col-b)]

                                 (t/testing "matching cell hidden per column"
                                   (t/is (true? (:hidden (get-shape' file' :copy-col-a-head))))
                                   (t/is (true? (:hidden (get-shape' file' :copy-col-b-head)))))

                                 (t/testing "other rows untouched"
                                   (doseq [label [:copy-col-a-cell1 :copy-col-a-cell2
                                                  :copy-col-b-cell1 :copy-col-b-cell2]]
                                     (t/is (nil? (:hidden (get-shape' file' label))))))

                                 (t/testing "hidden cells stay in :shapes at the same index"
                                   (t/is (= (:shapes col-a) (:shapes col-a')))
                                   (t/is (= (:shapes col-b) (:shapes col-b'))))

                                 (t/testing "root shrink: height - row height, width untouched"
                                   (t/is (= (- table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (t/testing "selection moves to the table root"
                                   (t/is (= #{(:id root)}
                                            (set (get-in new-state [:workspace-local :selected])))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest delete-column-hides-column-and-shrinks-width
  (t/testing "the clicked column is hidden in place and the root shrinks by the column width"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (setup-file)
                store     (ths/setup-store file)
                shape-id  (:id (cths/get-shape file :copy-col-a-cell1))
                events    [(dwt/delete-table-column shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'       (ths/get-file-from-state new-state)
                                     _           (assert-valid-file! file')
                                     root        (get-shape' file' :table-copy)
                                     root-before (:shapes (get-shape' file :table-copy))]

                                 (t/testing "column hidden, stays in root :shapes"
                                   (t/is (true? (:hidden (get-shape' file' :copy-col-a))))
                                   (t/is (= root-before (:shapes root))))

                                 (t/testing "other column untouched"
                                   (t/is (nil? (:hidden (get-shape' file' :copy-col-b)))))

                                 (t/testing "root shrink: width - column width, height untouched"
                                   (t/is (= (- table-w cell-w) (:width root)))
                                   (t/is (= table-h (:height root))))

                                 (t/testing "selection moves to the table root"
                                   (t/is (= #{(:id root)}
                                            (set (get-in new-state [:workspace-local :selected])))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-row-after-delete-skips-hidden
  (t/testing "after the bottom row was deleted, inserting from the new visible bottom row (cell1) clones each column's visible cell1 below it, not the hidden cell2 that keeps its stale geometry"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (setup-file)
                store     (ths/setup-store file)
                ;; the bottom row is deleted first: cell2 stays in :shapes,
                ;; hidden, with its stale bottom geometry; the insert is then
                ;; dispatched from cell1, now the visible bottom row
                cell2-id  (:id (cths/get-shape file :copy-col-a-cell2))
                events    [(dwt/delete-table-row cell2-id)
                           (dwt/insert-table-row (:id (cths/get-shape file :copy-col-a-cell1)))]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     src-a   (get-shape' file' :copy-col-a-cell1)
                                     src-b   (get-shape' file' :copy-col-b-cell1)]

                                 (t/testing "column A: clone of the visible cell1, inserted right before the hidden cell2"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 2))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell))
                                           "cloned from cell1, not the hidden cell2")
                                     ;; clone-subtree keeps the source geometry and no
                                     ;; layout runs in the test store, so the clone sits
                                     ;; at the :y of the cell it was cloned from
                                     (t/is (= (:y src-a) (:y new-cell)))))

                                 (t/testing "column B: clone of the visible cell1, inserted right after the hidden cell2 slot"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (nth (:shapes col-b) 1))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell))
                                           "cloned from cell1, not the hidden cell2")
                                     (t/is (= (:y src-b) (:y new-cell)))))

                                 (t/testing "hidden cells stay in :shapes, shifted past the inserts"
                                   (t/is (= 3 (.indexOf (:shapes col-a) (:id (get-shape' file' :copy-col-a-cell2)))))
                                   (t/is (= 0 (.indexOf (:shapes col-b) (:id (get-shape' file' :copy-col-b-cell2))))))

                                 (t/testing "root size: height back to the original, width untouched"
                                   (t/is (= table-h (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (t/testing "one commit per action, each with a uuid undo group"
                                   (t/is (= 2 (count @committed)))
                                   (t/is (uuid? (:undo-group (second @committed)))))))))))
        done))))

(t/deftest insert-row-from-hidden-cell-is-refused
  (t/testing "inserting from a hidden (deleted) cell is refused: no column has a visible cell at its stale row rank"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (setup-file)
                store     (ths/setup-store file)
                ;; the bottom row is deleted first: cell2 stays in :shapes,
                ;; hidden, so it no longer has a row rank among the visible
                ;; cells of its column
                shape-id  (:id (cths/get-shape file :copy-col-a-cell2))
                events    [(dwt/delete-table-row shape-id)
                           (dwt/insert-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)
                                     col-a (get-shape' file' :copy-col-a)]
                                 (t/is (= 1 (count @committed))
                                       "only the delete committed; the insert is refused")
                                 (t/is (= (- table-h cell-h) (:height root)))
                                 (t/is (= 3 (count (:shapes col-a))) "col-a unchanged by the insert")))))))
        done))))

(t/deftest insert-row-with-shorter-column-is-refused
  (t/testing "a row rank that some column has no visible cell for (a shorter column after a per-cell delete) is refused with no partial commit"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (-> (setup-file)
                              ;; col-b keeps only 2 visible cells (head, cell1):
                              ;; nothing sits at the rank of col-a cell2 (rank 2)
                              (cths/update-shape :copy-col-b-cell2 :hidden true))
                store     (ths/setup-store file)
                shape-id  (:id (cths/get-shape file :copy-col-a-cell2))
                events    [(dwt/insert-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)
                                     col-a (get-shape' file' :copy-col-a)
                                     col-b (get-shape' file' :copy-col-b)]
                                 (t/is (empty? @committed) "guard refuses before committing")
                                 (t/is (= table-h (:height root)))
                                 (t/is (= table-w (:width root)))
                                 (t/is (= 3 (count (:shapes col-a))) "col-a unchanged")
                                 (t/is (= 3 (count (:shapes col-b))) "col-b unchanged")))))))
        done))))

(t/deftest insert-column-after-delete-uses-visible-rightmost
  (t/testing "after col-b was deleted, inserting from the root clones the visible col-a, not the hidden col-b (still rightmost by its stale x)"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (setup-file)
                store     (ths/setup-store file)
                root-id   (:id (cths/get-shape file :table-copy))
                events    [(dwt/delete-table-column (:id (cths/get-shape file :copy-col-b-head)))
                           (dwt/insert-table-column root-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     new-col (get objects (nth (:shapes root) 1))]

                                 (t/testing "new column cloned from the visible col-a and inserted before the hidden col-b"
                                   (t/is (= 3 (count (:shapes root))))
                                   (t/is (some? new-col))
                                   (t/is (= (:component-id col-a) (:component-id new-col))
                                         "cloned from the visible col-a, not the hidden col-b"))

                                 (t/testing "root size: width back to the original, height untouched"
                                   (t/is (= table-w (:width root)))
                                   (t/is (= table-h (:height root))))

                                 (t/testing "one commit per action, each with a uuid undo group"
                                   (t/is (= 2 (count @committed)))
                                   (t/is (uuid? (:undo-group (second @committed)))))))))))
        done))))

(t/deftest delete-row-guard-blocks-last-row
  (t/testing "a row whose deletion would leave a column with a single visible cell is refused"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file     (-> (setup-file)
                             ;; every column keeps only its head visible
                             (cths/update-shape :copy-col-a-cell1 :hidden true)
                             (cths/update-shape :copy-col-a-cell2 :hidden true)
                             (cths/update-shape :copy-col-b-cell1 :hidden true)
                             (cths/update-shape :copy-col-b-cell2 :hidden true))
                store    (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-head))
                events   [(dwt/delete-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)]
                                 (t/is (empty? @committed) "guard refuses before committing")
                                 (t/is (= table-h (:height root)))
                                 (t/is (= table-w (:width root)))
                                 (t/is (nil? (:hidden (get-shape' file' :copy-col-a-head))))))))))
        done))))

(t/deftest delete-row-guard-blocks-missing-rank-target
  (t/testing "a row rank that some column has no visible cell for is refused"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file     (-> (setup-file)
                             ;; col-b keeps only 2 visible cells (head, cell1):
                             ;; nothing sits at the rank of col-a cell2 (rank 2)
                             (cths/update-shape :copy-col-b-cell2 :hidden true))
                store    (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-cell2))
                events   [(dwt/delete-table-row shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)]
                                 (t/is (empty? @committed) "guard refuses before committing")
                                 (t/is (= table-h (:height root)))
                                 (t/is (= table-w (:width root)))
                                 (t/is (nil? (:hidden (get-shape' file' :copy-col-a-cell2))))))))))
        done))))

(t/deftest delete-column-guard-blocks-last-column
  (t/testing "deleting the last visible column is refused"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file     (-> (setup-file)
                             (cths/update-shape :copy-col-b :hidden true))
                store    (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-cell1))
                events   [(dwt/delete-table-column shape-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)]
                                 (t/is (empty? @committed) "guard refuses before committing")
                                 (t/is (= table-w (:width root)))
                                 (t/is (= table-h (:height root)))
                                 (t/is (nil? (:hidden (get-shape' file' :copy-col-a))))))))))
        done))))

(t/deftest can-delete-predicates
  (t/testing "can-delete-table-row? / can-delete-table-column? mirror the event guards"
    ;; the guarded variants derive from the same `file`: a second setup-file
    ;; would re-register the labels in the idmap with fresh ids, and the
    ;; config set would no longer match the first file's component ids
    (let [file         (setup-file)
          objects      (page-objects file)
          cell         (get-shape' file :copy-col-a-cell1)
          root         (get-shape' file :table-copy)
          row-guarded  (-> file
                           ;; every column keeps only its head visible
                           (cths/update-shape :copy-col-a-cell1 :hidden true)
                           (cths/update-shape :copy-col-a-cell2 :hidden true)
                           (cths/update-shape :copy-col-b-cell1 :hidden true)
                           (cths/update-shape :copy-col-b-cell2 :hidden true))
          col-guarded  (-> file
                           ;; only one visible column remains
                           (cths/update-shape :copy-col-b :hidden true))]
      (with-redefs [cf/table-component-ids (config-with-table-comp)]
        (t/testing "from a cell of a configured table"
          (t/is (true? (dwt/can-delete-table-row? objects cell)))
          (t/is (true? (dwt/can-delete-table-column? objects cell))))

        (t/testing "at the guard boundary"
          (t/is (false? (dwt/can-delete-table-row? (page-objects row-guarded)
                                                   (get-shape' row-guarded :copy-col-a-head))))
          (t/is (false? (dwt/can-delete-table-column? (page-objects col-guarded)
                                                      (get-shape' col-guarded :copy-col-a-cell1)))))

        (t/testing "from the table root itself"
          (t/is (false? (dwt/can-delete-table-row? objects root)))
          (t/is (false? (dwt/can-delete-table-column? objects root))))))))

(t/deftest delete-from-root-or-column-is-noop
  (t/testing "row deletion from the root or a column itself, and column deletion from the root, do nothing"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file      (setup-file)
                store     (ths/setup-store file)
                root-id   (:id (cths/get-shape file :table-copy))
                column-id (:id (cths/get-shape file :copy-col-a))
                events    [(dwt/delete-table-row root-id)
                           (dwt/delete-table-row column-id)
                           (dwt/delete-table-column root-id)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)]
                                 (t/is (empty? @committed))
                                 (t/is (= table-h (:height root)))
                                 (t/is (= table-w (:width root)))
                                 (t/is (nil? (:hidden (get-shape' file' :copy-col-a-cell1))))))))))
        done))))

(t/deftest delete-row-undo-restores
  (t/testing "a single undo clears the hidden flags and restores the root height"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file     (setup-file)
                store    (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-cell1))
                events   [(dwt/delete-table-row shape-id)
                          dwu/undo]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (wire-undo-stack! store)
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file' (ths/get-file-from-state new-state)
                                     root  (get-shape' file' :table-copy)]
                                 (t/is (nil? (:hidden (get-shape' file' :copy-col-a-cell1))))
                                 (t/is (nil? (:hidden (get-shape' file' :copy-col-b-cell1))))
                                 (t/is (= table-h (:height root)))
                                 (t/is (= table-w (:width root)))))))))
        done))))

;; --- directional inserts (:above / :left) ---------------------------------
;;
;; Index truth table behind the expectations below (`slot` = the anchor's
;; position on its parent, `sign` = order-sign of the container :shapes vs
;; the visual axis, inferred from the stored geometry): on `:inc` a clone
;; below/right lands at (inc slot) and a clone above/left takes the anchor
;; slot itself, pushing the anchor further down the vector; on `:dec` the
;; :shapes vector runs against the visual axis, so the two directions swap
;; slots (below/right takes the anchor slot, above/left takes (inc slot)).
;; An unknown sign (fewer than two visible children) defaults to the :inc
;; mapping. The swap-slot machinery is unchanged and purely index-driven:
;; a child shifted onto the library position of another cell carries that
;; cell's slot, exactly like on the :below / :right paths.

(t/deftest insert-row-above-middle-row
  (t/testing "clicking a middle row with :above inserts the new row visually above it: each column clones its own rank-1 cell and inserts it before the clicked one"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                ;; cell1 sits at visual row rank 1 (head y0, cell1 y40, cell2 y80)
                shape-id (:id (cths/get-shape file :copy-col-a-cell1))
                events [(dwt/insert-table-row shape-id :above)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'     (ths/get-file-from-state new-state)
                                     _         (assert-valid-file! file')
                                     objects   (page-objects file')
                                     root      (get-shape' file' :table-copy)
                                     col-a     (get-shape' file' :copy-col-a)
                                     col-b     (get-shape' file' :copy-col-b)
                                     src-a     (get-shape' file' :copy-col-a-cell1)
                                     src-b     (get-shape' file' :copy-col-b-cell1)]

                                 (t/testing "column A (:shapes top->bottom) becomes [head NEW cell1 cell2]: :above on an :inc column takes the anchor slot itself, pushing the clicked row down"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 1))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))
                                     (t/is (not= (:id src-a) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-a-head) (nth (:shapes col-a) 0)))
                                   (t/is (= (cthi/id :copy-col-a-cell1) (nth (:shapes col-a) 2)))
                                   (t/is (= (cthi/id :copy-col-a-cell2) (nth (:shapes col-a) 3))))

                                 (t/testing "column B (:shapes bottom->top) becomes [cell2 cell1 NEW head]: :above on a :dec column is the mirrored slot, each column clones its OWN rank-1 cell"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (nth (:shapes col-b) 2))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell))
                                           "cloned from the col-b cell1, not from the clicked col-a cell1")
                                     (t/is (not= (:id src-b) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-b-cell2) (nth (:shapes col-b) 0)))
                                   (t/is (= (cthi/id :copy-col-b-cell1) (nth (:shapes col-b) 1)))
                                   (t/is (= (cthi/id :copy-col-b-head) (nth (:shapes col-b) 3))))

                                 (t/testing "cloned subtree has new ids and keeps children"
                                   (let [new-cell (get objects (nth (:shapes col-a) 1))
                                         new-inner (get objects (first (:shapes new-cell)))]
                                     (t/is (some? new-inner))
                                     (t/is (= (:shape-ref (get-shape' file' :copy-col-a-cell1-inner))
                                              (:shape-ref new-inner)))
                                     (t/is (not= (:id (get-shape' file' :copy-col-a-cell1-inner))
                                                 (:id new-inner)))))

                                 (t/testing "swap slots keep the file valid for the referential-integrity checker"
                                   ;; col-a: the clone lands exactly on the library position
                                   ;; of the cell it was cloned from, so it needs no slot of
                                   ;; its own; the clicked cell1, pushed one position down,
                                   ;; takes the library position of cell2 and must carry its
                                   ;; slot; cell2 falls beyond the library children.
                                   (t/is (nil? (ctk/get-swap-slot (get objects (nth (:shapes col-a) 1)))))
                                   (t/is (= (:shape-ref (get-shape' file' :copy-col-a-cell2))
                                            (ctk/get-swap-slot (get-shape' file' :copy-col-a-cell1))))
                                   (doseq [cell-id [(cthi/id :copy-col-a-head)
                                                    (cthi/id :copy-col-a-cell2)]]
                                     (t/is (nil? (ctk/get-swap-slot (get objects cell-id)))))
                                   ;; col-b: the clone sits at the library position of the
                                   ;; head while its :shape-ref points at the library cell1,
                                   ;; so it carries the head's slot; every other cell stays
                                   ;; aligned (cell2, cell1) or falls beyond the library
                                   ;; children (the head, pushed to index 3).
                                   (t/is (= (:shape-ref (get-shape' file' :copy-col-b-head))
                                            (ctk/get-swap-slot (get objects (nth (:shapes col-b) 2)))))
                                   (doseq [label [:copy-col-b-cell2 :copy-col-b-cell1 :copy-col-b-head]]
                                     (t/is (nil? (ctk/get-swap-slot (get-shape' file' label))))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-row-above-head-row
  (t/testing "clicking the head row with :above puts the new row at the visual top: index 0 on the :inc column, past the head on the :dec one"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-head))
                events [(dwt/insert-table-row shape-id :above)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     head-a  (get-shape' file' :copy-col-a-head)
                                     head-b  (get-shape' file' :copy-col-b-head)]

                                 (t/testing "column A (:shapes top->bottom) becomes [NEW head cell1 cell2]"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 0))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id head-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref head-a) (:shape-ref new-cell)))
                                     (t/is (not= (:id head-a) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-a-head) (nth (:shapes col-a) 1)))
                                   (t/is (= (cthi/id :copy-col-a-cell1) (nth (:shapes col-a) 2)))
                                   (t/is (= (cthi/id :copy-col-a-cell2) (nth (:shapes col-a) 3))))

                                 (t/testing "column B (:shapes bottom->top) becomes [cell2 cell1 head NEW]: cloned from the col-b head, not from col-a's"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (nth (:shapes col-b) 3))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id head-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref head-b) (:shape-ref new-cell)))
                                     (t/is (not= (:id head-b) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-b-cell2) (nth (:shapes col-b) 0)))
                                   (t/is (= (cthi/id :copy-col-b-cell1) (nth (:shapes col-b) 1)))
                                   (t/is (= (cthi/id :copy-col-b-head) (nth (:shapes col-b) 2))))

                                 (t/testing "swap slots keep the file valid for the referential-integrity checker"
                                   ;; col-a: the clone lands on the library position of the
                                   ;; head it was cloned from (no slot); the head and cell1
                                   ;; are each pushed one position down, so each must carry
                                   ;; the slot of the library cell whose position it takes
                                   ;; over; cell2 falls beyond the library children.
                                   (t/is (nil? (ctk/get-swap-slot (get objects (nth (:shapes col-a) 0)))))
                                   (t/is (= (:shape-ref (get-shape' file' :copy-col-a-cell1))
                                            (ctk/get-swap-slot (get-shape' file' :copy-col-a-head))))
                                   (t/is (= (:shape-ref (get-shape' file' :copy-col-a-cell2))
                                            (ctk/get-swap-slot (get-shape' file' :copy-col-a-cell1))))
                                   (t/is (nil? (ctk/get-swap-slot (get-shape' file' :copy-col-a-cell2))))
                                   ;; col-b: the clone is appended beyond the library
                                   ;; children and every existing cell stays aligned:
                                   ;; no slot anywhere in the column.
                                   (doseq [id (:shapes col-b)]
                                     (t/is (nil? (ctk/get-swap-slot (get objects id))))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-row-above-clicked-bottom-row
  (t/testing "clicking the bottom row with :above inserts the new row between it and the middle row, in both column orders"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-cell2))
                events [(dwt/insert-table-row shape-id :above)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     src-a   (get-shape' file' :copy-col-a-cell2)
                                     src-b   (get-shape' file' :copy-col-b-cell2)]

                                 (t/testing "column A (:shapes top->bottom) becomes [head cell1 NEW cell2]: the clone takes the clicked row's slot, the clicked row moves past it"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 2))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))
                                     (t/is (not= (:id src-a) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-a-head) (nth (:shapes col-a) 0)))
                                   (t/is (= (cthi/id :copy-col-a-cell1) (nth (:shapes col-a) 1)))
                                   (t/is (= (cthi/id :copy-col-a-cell2) (nth (:shapes col-a) 3))))

                                 (t/testing "column B (:shapes bottom->top) becomes [cell2 NEW cell1 head]: the clone goes one position further into the vector, i.e. visually above the clicked bottom row"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (nth (:shapes col-b) 1))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell))
                                           "cloned from the col-b cell2, not from the clicked col-a cell2")
                                     (t/is (not= (:id src-b) (:id new-cell))))
                                   (t/is (= (cthi/id :copy-col-b-cell2) (nth (:shapes col-b) 0)))
                                   (t/is (= (cthi/id :copy-col-b-cell1) (nth (:shapes col-b) 2)))
                                   (t/is (= (cthi/id :copy-col-b-head) (nth (:shapes col-b) 3))))

                                 (t/testing "swap slots keep the file valid for the referential-integrity checker"
                                   ;; col-a: the clone lands exactly on the library position
                                   ;; of the cell it was cloned from while the clicked cell2
                                   ;; is pushed beyond the library children: no slot anywhere
                                   ;; (unlike the :below append, which left the column
                                   ;; slot-free by cloning past the library children).
                                   (doseq [cell-id (:shapes col-a)]
                                     (t/is (nil? (ctk/get-swap-slot (get objects cell-id)))))
                                   ;; col-b: the clone sits at the library position of cell1
                                   ;; while its :shape-ref points at the library cell2, and
                                   ;; the shifted cell1 takes the library position of the
                                   ;; head; the head falls beyond the library children.
                                   (t/is (= (:shape-ref (get-shape' file' :copy-col-b-cell1))
                                            (ctk/get-swap-slot (get objects (nth (:shapes col-b) 1)))))
                                   (t/is (= (:shape-ref (get-shape' file' :copy-col-b-head))
                                            (ctk/get-swap-slot (get-shape' file' :copy-col-b-cell1))))
                                   (t/is (nil? (ctk/get-swap-slot (get-shape' file' :copy-col-b-cell2)))
                                         "aligned with its library counterpart: no slot")
                                   (t/is (nil? (ctk/get-swap-slot (get-shape' file' :copy-col-b-head)))
                                         "beyond the library children: no slot"))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-row-from-root-falls-back-to-top-append
  (t/testing "inserting :above from the table root itself (no clicked cell to resolve) clones each column's visually top-most visible cell and puts the new row at the visual top"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                root-id (:id (cths/get-shape file :table-copy))
                events  [(dwt/insert-table-row root-id :above)]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     src-a   (get-shape' file' :copy-col-a-head)
                                     src-b   (get-shape' file' :copy-col-b-head)]

                                 (t/testing "column A (:shapes top->bottom): new cell at index 0, cloned from the head (the visually top-most visible cell)"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 0))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell))))
                                   (t/is (= (cthi/id :copy-col-a-head) (nth (:shapes col-a) 1))))

                                 (t/testing "column B (:shapes bottom->top): new cell at the END (visually the top), cloned from the head"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (peek (:shapes col-b)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell))))
                                   (t/is (= (cthi/id :copy-col-b-head) (nth (:shapes col-b) 2))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))))))))
        done))))

(t/deftest insert-row-from-column-falls-back-to-top-append
  (t/testing "inserting :above from a column frame itself (no clicked cell to resolve) keeps the top-append fallback"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                col-id  (:id (cths/get-shape file :copy-col-a))
                events  [(dwt/insert-table-row col-id :above)]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)
                                     src-a   (get-shape' file' :copy-col-a-head)
                                     src-b   (get-shape' file' :copy-col-b-head)]

                                 (t/testing "column A (:shapes top->bottom): new cell at index 0, cloned from the head"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (nth (:shapes col-a) 0))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))))

                                 (t/testing "column B (:shapes bottom->top): new cell at the END (visually the top), cloned from the head"
                                   (t/is (= 4 (count (:shapes col-b))))
                                   (let [new-cell (get objects (peek (:shapes col-b)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-b) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-b) (:shape-ref new-cell)))))

                                 (t/testing "root resize: height + cell height, width untouched"
                                   (t/is (= (+ table-h cell-h) (:height root)))
                                   (t/is (= table-w (:width root))))))))))
        done))))

(t/deftest insert-column-left-of-leftmost-column
  (t/testing "a full clone of the clicked leftmost column is inserted to its left (root index 0), root grows by column width"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-a-cell1))
                events [(dwt/insert-table-column shape-id :left)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)]

                                 (t/testing "root (:shapes left->right) becomes [NEW col-a col-b]: :left on an :inc root takes the target slot itself, pushing the clicked column right"
                                   (t/is (= 3 (count (:shapes root))))
                                   (let [new-col (get objects (nth (:shapes root) 0))]
                                     (t/is (some? new-col))
                                     (t/is (not= (:id col-a) (:id new-col)) "col-a shifted, new column sits before it")
                                     (t/is (= (:component-id col-a) (:component-id new-col)))
                                     ;; nested instance heads inside a copy keep :component-id
                                     ;; but only the top instance root carries :component-root
                                     (t/is (= (:component-root col-a) (:component-root new-col)))
                                     (t/testing "cloned column keeps its 3 cells with refs"
                                       (t/is (= 3 (count (:shapes new-col))))
                                       (t/is (= (mapv :shape-ref (map objects (:shapes col-a)))
                                                (mapv :shape-ref (map objects (:shapes new-col)))))))
                                   (t/is (= (:id col-a) (nth (:shapes root) 1)))
                                   (t/is (= (:id col-b) (nth (:shapes root) 2))))

                                 (t/testing "swap slots keep the file valid for the referential-integrity checker"
                                   ;; the new column sits on the library position of col-a
                                   ;; itself (the column it was cloned from), so it needs no
                                   ;; slot; the shifted col-a takes the library position of
                                   ;; col-b and must carry its slot; col-b falls beyond the
                                   ;; library children.
                                   (let [new-col (get objects (nth (:shapes root) 0))]
                                     (t/is (nil? (ctk/get-swap-slot new-col))))
                                   (t/is (= (:shape-ref col-b) (ctk/get-swap-slot col-a)))
                                   (t/is (nil? (ctk/get-swap-slot col-b))
                                         "beyond the library children: no slot"))

                                 (t/testing "root resize: width + column width, height untouched"
                                   (t/is (= (+ table-w cell-w) (:width root)))
                                   (t/is (= table-h (:height root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-column-left-of-right-column
  (t/testing "a clone of the clicked right column inserted to its left lands on its own library position: no slot changes anywhere"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file  (setup-file)
                store (ths/setup-store file)
                shape-id (:id (cths/get-shape file :copy-col-b-cell1))
                events [(dwt/insert-table-column shape-id :left)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     col-b   (get-shape' file' :copy-col-b)]

                                 (t/testing "root (:shapes left->right) becomes [col-a NEW col-b]: the new column takes col-b's slot, col-b moves past it"
                                   (t/is (= 3 (count (:shapes root))))
                                   (let [new-col (get objects (nth (:shapes root) 1))]
                                     (t/is (some? new-col))
                                     (t/is (not= (:id col-b) (:id new-col)) "col-b shifted, new column sits before it")
                                     (t/is (= (:component-id col-b) (:component-id new-col))
                                           "cloned from the clicked col-b, not from col-a")
                                     (t/testing "cloned column keeps its 3 cells with refs"
                                       (t/is (= 3 (count (:shapes new-col))))
                                       (t/is (= (mapv :shape-ref (map objects (:shapes col-b)))
                                                (mapv :shape-ref (map objects (:shapes new-col)))))))
                                   (t/is (= (:id col-a) (nth (:shapes root) 0)))
                                   (t/is (= (:id col-b) (nth (:shapes root) 2))))

                                 (t/testing "swap slots keep the file valid for the referential-integrity checker"
                                   ;; the clone lands exactly on the library position of the
                                   ;; column it was cloned from while col-a stays aligned and
                                   ;; the shifted col-b falls beyond the library children:
                                   ;; no slot anywhere in the root.
                                   (doseq [col-id (:shapes root)]
                                     (t/is (nil? (ctk/get-swap-slot (get objects col-id))))))

                                 (t/testing "root resize: width + column width, height untouched"
                                   (t/is (= (+ table-w cell-w) (:width root)))
                                   (t/is (= table-h (:height root))))

                                 (assert-undo-group-uuid! committed)))))))
        done))))

(t/deftest insert-column-from-root-falls-back-to-leftmost
  (t/testing "inserting :left from the table root itself clones the leftmost column and inserts before it"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                root-id (:id (cths/get-shape file :table-copy))
                events  [(dwt/insert-table-column root-id :left)]]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-a   (get-shape' file' :copy-col-a)
                                     new-col (get objects (nth (:shapes root) 0))]
                                 (t/is (= 3 (count (:shapes root))))
                                 (t/is (some? new-col))
                                 (t/is (= (:component-id col-a) (:component-id new-col))
                                       "cloned from the leftmost column (col-a)")
                                 (t/is (= (:id col-a) (nth (:shapes root) 1)))
                                 (t/testing "root resize: width + column width, height untouched"
                                   (t/is (= (+ table-w cell-w) (:width root)))
                                   (t/is (= table-h (:height root))))))))))
        done))))

(t/deftest insert-column-left-after-delete-uses-visible-leftmost
  (t/testing "after col-a was deleted, inserting :left from the root clones the visible col-b, not the hidden col-a (still leftmost by its stale x)"
    ;; mirror of insert-column-after-delete-uses-visible-rightmost with the
    ;; directions flipped: the hidden column is the LEFT-most one here
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file    (setup-file)
                store   (ths/setup-store file)
                root-id (:id (cths/get-shape file :table-copy))
                events  [(dwt/delete-table-column (:id (cths/get-shape file :copy-col-a-head)))
                         (dwt/insert-table-column root-id :left)]
                committed (volatile! [])]
            (with-redefs [cf/table-component-ids (config-with-table-comp)
                          dch/commit-changes (capture-commit-fn committed)]
              (ths/run-store store done' events
                             (fn [new-state]
                               (let [file'   (ths/get-file-from-state new-state)
                                     _       (assert-valid-file! file')
                                     objects (page-objects file')
                                     root    (get-shape' file' :table-copy)
                                     col-b   (get-shape' file' :copy-col-b)
                                     new-col (get objects (nth (:shapes root) 1))]

                                 (t/testing "new column cloned from the visible col-b and inserted after the hidden col-a slot"
                                   (t/is (= 3 (count (:shapes root))))
                                   (t/is (some? new-col))
                                   (t/is (= (:component-id col-b) (:component-id new-col))
                                         "cloned from the visible col-b, not the hidden col-a")
                                   (t/is (= (cthi/id :copy-col-a) (nth (:shapes root) 0))
                                         "hidden col-a keeps its :shapes slot")
                                   (t/is (= (:id col-b) (nth (:shapes root) 2))))

                                 (t/testing "root size: width back to the original, height untouched"
                                   (t/is (= table-w (:width root)))
                                   (t/is (= table-h (:height root))))

                                 (t/testing "one commit per action, each with a uuid undo group"
                                   (t/is (= 2 (count @committed)))
                                   (t/is (uuid? (:undo-group (second @committed)))))))))))
        done))))

(t/deftest insert-row-explicit-below-same-as-default
  (t/testing "the single-argument arity dispatches :below: the same cell in two identical fixtures yields identical resulting orders"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [;; first run: the 1-arity default
                file-dflt  (setup-file)
                store-dflt (ths/setup-store file-dflt)
                id-dflt    (:id (cths/get-shape file-dflt :copy-col-a-cell1))]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              ;; run-store invokes its done argument on completion: a no-op
              ;; for the first run, so the mock restore stays wired to the
              ;; second one (the streams are synchronous, like every other
              ;; test here relies on)
              (ths/run-store store-dflt (fn []) [(dwt/insert-table-row id-dflt)]
                             (fn [state-dflt]
                               (let [sig-dflt (mapv (fn [label] (order-signature state-dflt label :y))
                                                    [:copy-col-a :copy-col-b])]
                                 ;; second run: the explicit :below on a fresh,
                                 ;; identically built fixture (its ids all differ)
                                 (let [file-expl  (setup-file)
                                       store-expl (ths/setup-store file-expl)
                                       id-expl    (:id (cths/get-shape file-expl :copy-col-a-cell1))]
                                   (with-redefs [cf/table-component-ids (config-with-table-comp)]
                                     (ths/run-store store-expl done' [(dwt/insert-table-row id-expl :below)]
                                                    (fn [state-expl]
                                                      (let [file'   (ths/get-file-from-state state-expl)
                                                            _       (assert-valid-file! file')
                                                            objects (page-objects file')
                                                            root    (get-shape' file' :table-copy)
                                                            col-a   (get-shape' file' :copy-col-a)
                                                            col-b   (get-shape' file' :copy-col-b)]

                                                        (t/testing "explicit :below matches insert-row-below-middle-row"
                                                          (t/is (= 4 (count (:shapes col-a))))
                                                          (t/is (some? (get objects (nth (:shapes col-a) 2))))
                                                          (t/is (= (cthi/id :copy-col-a-head) (nth (:shapes col-a) 0)))
                                                          (t/is (= (cthi/id :copy-col-a-cell1) (nth (:shapes col-a) 1)))
                                                          (t/is (= (cthi/id :copy-col-a-cell2) (nth (:shapes col-a) 3)))
                                                          (t/is (= 4 (count (:shapes col-b))))
                                                          (t/is (some? (get objects (nth (:shapes col-b) 1))))
                                                          (t/is (= (cthi/id :copy-col-b-cell2) (nth (:shapes col-b) 0)))
                                                          (t/is (= (cthi/id :copy-col-b-cell1) (nth (:shapes col-b) 2)))
                                                          (t/is (= (cthi/id :copy-col-b-head) (nth (:shapes col-b) 3))))

                                                        (t/testing "identical resulting orders (labels + slot placement) as the 1-arity default"
                                                          (t/is (= sig-dflt
                                                                   (mapv (fn [label] (order-signature state-expl label :y))
                                                                         [:copy-col-a :copy-col-b]))))

                                                        (t/testing "root resize: height + cell height, width untouched"
                                                          (t/is (= (+ table-h cell-h) (:height root)))
                                                          (t/is (= table-w (:width root)))))))))))))))
        done))))

(t/deftest insert-column-explicit-right-same-as-default
  (t/testing "the single-argument arity dispatches :right: the same cell in two identical fixtures yields identical resulting root orders"
    (t/async done
      (mock/with-mocks {uuid/next cthi/next-uuid}
        (fn [done']
          (let [file-dflt  (setup-file)
                store-dflt (ths/setup-store file-dflt)
                id-dflt    (:id (cths/get-shape file-dflt :copy-col-a-cell1))]
            (with-redefs [cf/table-component-ids (config-with-table-comp)]
              ;; no-op done for the first run, like the row test above
              (ths/run-store store-dflt (fn []) [(dwt/insert-table-column id-dflt)]
                             (fn [state-dflt]
                               (let [sig-dflt (order-signature state-dflt :table-copy :x)]
                                 (let [file-expl  (setup-file)
                                       store-expl (ths/setup-store file-expl)
                                       id-expl    (:id (cths/get-shape file-expl :copy-col-a-cell1))]
                                   (with-redefs [cf/table-component-ids (config-with-table-comp)]
                                     (ths/run-store store-expl done' [(dwt/insert-table-column id-expl :right)]
                                                    (fn [state-expl]
                                                      (let [file'   (ths/get-file-from-state state-expl)
                                                            _       (assert-valid-file! file')
                                                            objects (page-objects file')
                                                            root    (get-shape' file' :table-copy)
                                                            col-a   (get-shape' file' :copy-col-a)
                                                            col-b   (get-shape' file' :copy-col-b)]

                                                        (t/testing "explicit :right matches insert-column-right-of-clicked-column"
                                                          (t/is (= 3 (count (:shapes root))))
                                                          (t/is (some? (get objects (nth (:shapes root) 1))))
                                                          (t/is (= (:id col-a) (nth (:shapes root) 0)))
                                                          (t/is (= (:id col-b) (nth (:shapes root) 2))))

                                                        (t/testing "identical resulting root order (labels + slot placement) as the 1-arity default"
                                                          (t/is (= sig-dflt
                                                                   (order-signature state-expl :table-copy :x))))

                                                        (t/testing "root resize: width + column width, height untouched"
                                                          (t/is (= (+ table-w cell-w) (:width root)))
                                                          (t/is (= table-h (:height root)))))))))))))))
        done))))
