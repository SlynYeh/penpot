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
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]))

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
;; prove that each column clones its own bottom cell.

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
  (t/is (= 1 (count @committed)) "exactly one commit for the insert")
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

(t/deftest insert-row-appends-bottom-cell-per-column
  (t/testing "each column gains a clone of its own bottom cell at the visual bottom, root grows by row height"
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

                                 (t/testing "column A (:shapes top->bottom): new cell appended at END"
                                   (t/is (= 4 (count (:shapes col-a))))
                                   (let [new-cell (get objects (peek (:shapes col-a)))]
                                     (t/is (some? new-cell))
                                     (t/is (= (:component-id src-a) (:component-id new-cell)))
                                     (t/is (= (:shape-ref src-a) (:shape-ref new-cell)))
                                     (t/is (not= (:id src-a) (:id new-cell)))))

                                 (t/testing "column B (:shapes bottom->top): new cell inserted at index 0"
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
