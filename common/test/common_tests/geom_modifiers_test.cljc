;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.geom-modifiers-test
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.grid-layout.layout-data :as glld]
   [app.common.geom.shapes.points :as gpo]
   [app.common.math :as mth]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Helpers

(defn- add-flex-frame
  "Create a flex layout frame"
  [file frame-label & {:keys [width height] :as params}]
  (ths/add-sample-shape file frame-label
                        (merge {:type :frame
                                :name "FlexFrame"
                                :layout-flex-dir :row
                                :width (or width 200)
                                :height (or height 200)}
                               params)))

(defn- add-rect-child
  "Create a rectangle child inside a parent"
  [file rect-label parent-label & {:keys [width height x y] :as params}]
  (ths/add-sample-shape file rect-label
                        (merge {:type :rect
                                :name "Rect"
                                :parent-label parent-label
                                :width (or width 50)
                                :height (or height 50)
                                :x (or x 0)
                                :y (or y 0)}
                               params)))

(defn- add-ghost-child-id
  "Add a non-existent child ID to a frame's shapes list.
   This simulates data inconsistency where a child ID is referenced
   but the child shape doesn't exist in objects."
  [file frame-label ghost-id]
  (let [page (thf/current-page file)
        frame-id (thi/id frame-label)]
    (update file :data
            (fn [file-data]
              (update-in file-data [:pages-index (:id page) :objects frame-id :shapes]
                         conj ghost-id)))))

;; ---- Tests

(t/deftest flex-layout-with-normal-children
  (t/testing "set-objects-modifiers processes flex layout children correctly"
    (let [file    (-> (thf/sample-file :file1)
                      (add-flex-frame :frame1)
                      (add-rect-child :rect1 :frame1))
          page    (thf/current-page file)
          objects (:objects page)
          rect-id  (thi/id :rect1)

          ;; Create a move modifier for the rectangle
          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}

          ;; This should not crash
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      ;; The rectangle should have modifiers
      (t/is (contains? result rect-id)))))

(t/deftest flex-layout-with-nonexistent-child
  (t/testing "set-objects-modifiers handles flex frame with non-existent child in shapes"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (add-flex-frame :frame1)
                       (add-rect-child :rect1 :frame1)
                       ;; Add a non-existent child ID to the frame's shapes
                       (add-ghost-child-id :frame1 ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          rect-id  (thi/id :rect1)

          ;; Create a move modifier for the existing rectangle
          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}

          ;; This should NOT crash even though the flex frame has
          ;; a child ID (ghost-id) that doesn't exist in objects
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      (t/is (contains? result rect-id)))))

(t/deftest flex-layout-with-all-ghost-children
  (t/testing "set-objects-modifiers handles flex frame with only non-existent children"
    (let [ghost1 (thi/next-uuid)
          ghost2 (thi/next-uuid)
          file   (-> (thf/sample-file :file1)
                     (add-flex-frame :frame1)
                     ;; Add only non-existent children to the frame's shapes
                     (add-ghost-child-id :frame1 ghost1)
                     (add-ghost-child-id :frame1 ghost2))
          page    (thf/current-page file)
          objects (:objects page)
          frame-id (thi/id :frame1)

          ;; Create a move modifier for the frame itself
          modif-tree {frame-id {:modifiers (ctm/move-modifiers (gpt/point 5 5))}}

          ;; Should not crash even though the flex frame has
          ;; no existing children in its shapes list
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result)))))

(t/deftest grid-layout-with-nonexistent-child
  (t/testing "set-objects-modifiers handles grid frame with non-existent child in shapes"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (ths/add-sample-shape :frame1
                                             {:type :frame
                                              :name "GridFrame"
                                              :layout-grid-dir :row
                                              :width 200
                                              :height 200})
                       (add-rect-child :rect1 :frame1)
                       (add-ghost-child-id :frame1 ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          rect-id  (thi/id :rect1)

          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}

          ;; Should not crash for grid layout with ghost child
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      (t/is (contains? result rect-id)))))

(t/deftest flex-layout-resize-with-nonexistent-child
  (t/testing "resize modifier propagation handles non-existent children"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (add-flex-frame :frame1)
                       (add-rect-child :rect1 :frame1)
                       (add-ghost-child-id :frame1 ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          frame-id (thi/id :frame1)

          ;; Create a resize modifier for the frame itself
          modif-tree {frame-id {:modifiers (ctm/resize-modifiers
                                            (gpt/point 2 2)
                                            (gpt/point 0 0))}}

          ;; Should not crash when propagating resize through flex layout
          ;; that has ghost children
          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      ;; The frame should have modifiers
      (t/is (contains? result frame-id)))))

(t/deftest nested-flex-layout-with-nonexistent-child
  (t/testing "nested flex layout handles non-existent children in outer frame"
    (let [ghost-id (thi/next-uuid)
          file     (-> (thf/sample-file :file1)
                       (add-flex-frame :outer-frame)
                       (add-flex-frame :inner-frame :parent-label :outer-frame)
                       (add-rect-child :rect1 :inner-frame)
                       (add-ghost-child-id :outer-frame ghost-id))
          page     (thf/current-page file)
          objects  (:objects page)
          rect-id  (thi/id :rect1)

          modif-tree {rect-id {:modifiers (ctm/move-modifiers (gpt/point 5 10))}}

          result (gm/set-objects-modifiers modif-tree objects)]

      (t/is (some? result))
      (t/is (contains? result rect-id)))))

;; ---- calc-layout-data cache tests (direct, meaningful)
;;
;; The cache wrapper (`glld/calc-layout-data`) delegates to `calc-layout-data*`
;; when `*grid-layout-cache*` is nil (truly uncached) and otherwise keys on a
;; fingerprint of its inputs. These tests call `glld/calc-layout-data` directly
;; (NOT via `gm/set-objects-modifiers`, which binds the cache internally) so we
;; control precisely whether the cache is bound.
;;
;; Inputs are built the same way the grid editor does
;; (frontend/.../grid_layout_editor.cljs:1032-1041): the bounds map is keyed by
;; ALL descendants (not just immediate children) because `child-min-*` strict
;; derefs `@(get bounds <grandchild-id>)` for fill-width layout-frame children.

;; Fixed ids so the objects map can be rebuilt with a resized grandchild while
;; keeping parent/child identities (and thus the fingerprint) stable.
(def ^:private cache-p-id #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private cache-c-id #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private cache-g-id #uuid "00000000-0000-0000-0000-0000000000a3")

(defn- calc-layout-inputs
  "Build `[parent transformed-parent-bounds children bounds objects]` for
  `glld/calc-layout-data`, mirroring the grid editor call site. The bounds map
  is keyed by every descendant so grandchild derefs resolve."
  [objects frame-id]
  (let [parent   (get objects frame-id)
        tp-bounds (:points parent)
        children  (->> (cfh/get-immediate-children objects frame-id {:remove-hidden true})
                       (map #(vector (gpo/parent-coords-bounds (:points %) (:points parent)) %)))
        desc-ids  (cfh/get-children-ids objects frame-id)
        bounds    (d/lazy-map desc-ids #(gco/shape->points (get objects %)))]
    [parent tp-bounds children bounds objects]))

(defn- grid-auto-parent-frame
  "Grid frame with one AUTO column (so `set-auto-base-size` grows it to the
  child's min-width) and one fixed row. `child-ids` become single-span cells
  in column 1 / row 1."
  [id child-ids]
  (assoc (cts/setup-shape
          {:type :frame
           :name "GridParent"
           :layout :grid
           :layout-grid-dir :row
           :layout-grid-columns [{:type :auto :value 1}]
           :layout-grid-rows [{:type :fixed :value 100.0}]
           :layout-grid-cells (into {}
                                    (map-indexed (fn [i cid]
                                                   [(str "cell-" i)
                                                    {:shapes [cid]
                                                     :column 1 :row 1
                                                     :column-span 1 :row-span 1}])
                                                 child-ids))
           :layout-padding-type :multiple
           :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
           :layout-gap {:row-gap 0 :column-gap 0}
           :x 0 :y 0 :width 300 :height 100})
         :id id
         :shapes (vec child-ids)))

(t/deftest calc-layout-data-cache-basic-equivalence
  (t/testing "calc-layout-data: cached miss+hit == truly-uncached, and the cache dedups to one entry"
    (let [r-id (random-uuid)
          objects {cache-p-id (grid-auto-parent-frame cache-p-id [r-id])
                   r-id       (assoc (cts/setup-shape
                                      {:type :rect
                                       :name "Rect"
                                       :x 0 :y 0 :width 60 :height 40})
                                     :id r-id :parent-id cache-p-id)}
          [parent tp-bounds children bounds objs] (calc-layout-inputs objects cache-p-id)
          uncached (glld/calc-layout-data parent tp-bounds children bounds objs)
          cache    (atom {})
          miss     (binding [glld/*grid-layout-cache* cache]
                     (glld/calc-layout-data parent tp-bounds children bounds objs))
          hit      (binding [glld/*grid-layout-cache* cache]
                     (glld/calc-layout-data parent tp-bounds children bounds objs))]
      (t/is (= uncached miss) "first cached call (miss) equals the uncached result")
      (t/is (= uncached hit)  "second cached call (hit) equals the uncached result")
      ;; Value equality alone can't distinguish "returned the stored object"
      ;; from "re-solved to an equal value" — assert identity on the hit path.
      (t/is (identical? miss hit) "hit returns the exact stored object, not a re-solve")
      ;; Two calls with identical inputs must store exactly one entry (dedup).
      (t/is (= 1 (count @cache)) "two identical calls produce exactly one cache entry")
      ;; Sanity: the auto column actually grew to the child width (60), proving
      ;; the result is meaningful and child-min-width was exercised.
      (t/is (mth/close? 60.0 (-> uncached :column-tracks first :size) 0.001)
            "auto column track grew to the child's width"))))

(t/deftest calc-layout-data-cache-nested-frame-grandchild-resize
  (t/testing "resizing a grandchild inside a fill-width layout-frame child invalidates the parent cache (no stale hit)"
    ;; Grid parent P -> flex frame C (fill-width, has its own layout) -> rect G.
    ;; `child-min-width(C)` (strict, flex branch) derefs `@(get bounds g-id)`
    ;; via `layout-content-bounds`, so G's size affects P's result. But C is a
    ;; fixed-size frame, so its OWN bounds (the only thing P's plain fingerprint
    ;; hashes for C) do not change when G is resized. If the fingerprint does not
    ;; capture the grandchild, the second call under the same atom is a stale hit.
    (let [build (fn [g-width]
                  {cache-p-id (grid-auto-parent-frame cache-p-id [cache-c-id])
                   cache-c-id (assoc (cts/setup-shape
                                      {:type :frame
                                       :name "FlexChild"
                                       :layout :flex
                                       :layout-flex-dir :row
                                       :layout-item-h-sizing :fill
                                       :layout-padding-type :multiple
                                       :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
                                       :layout-gap {:row-gap 0 :column-gap 0}
                                       :x 0 :y 0 :width 100 :height 80})
                                     :id cache-c-id :parent-id cache-p-id :shapes [cache-g-id])
                   cache-g-id (assoc (cts/setup-shape
                                      {:type :rect
                                       :name "Grandchild"
                                       :x 0 :y 0 :width g-width :height 40})
                                     :id cache-g-id :parent-id cache-c-id)})
          objects1 (build 50)
          [parent tp-bounds children bounds objs] (calc-layout-inputs objects1 cache-p-id)

          r1    (glld/calc-layout-data parent tp-bounds children bounds objs)
          cache (atom {})
          miss  (binding [glld/*grid-layout-cache* cache]
                  (glld/calc-layout-data parent tp-bounds children bounds objs))
          hit   (binding [glld/*grid-layout-cache* cache]
                  (glld/calc-layout-data parent tp-bounds children bounds objs))]

      (t/is (= r1 miss hit) "cached miss+hit equals uncached for the nested-frame case")
      (t/is (= 1 (count @cache)) "one cache entry after the initial pair of calls")

      ;; Now resize the grandchild. C's own points (and thus P's direct-child
      ;; fingerprint slice for C) are unchanged -- only a grandchild bound moves.
      (let [objects2 (build 200)
            inputs2  (calc-layout-inputs objects2 cache-p-id)
            r2-uncached (apply glld/calc-layout-data inputs2)
            ;; DECISIVE: same cache atom (already holds fp(r1)). A complete
            ;; fingerprint misses here and returns the fresh result; an
            ;; incomplete one hits and returns the stale r1.
            r2-cached (binding [glld/*grid-layout-cache* cache]
                        (apply glld/calc-layout-data inputs2))]
        (t/is (not= r1 r2-uncached)
              "resizing the grandchild changes the uncached result (input change matters)")
        (t/is (= r2-uncached r2-cached)
              "cached result after grandchild resize equals fresh uncached (no stale hit)")))))

;; ---- skip-live-solve? --------------------------------------------------

(defn- sm-shape
  ([id type parent-id] (sm-shape id type parent-id [] nil))
  ([id type parent-id shapes] (sm-shape id type parent-id shapes nil))
  ([id type parent-id shapes layout]
   (cond-> {:id id :type type :parent-id parent-id :shapes (vec shapes)}
     (some? layout) (assoc :layout layout))))

(t/deftest skip-live-solve-test
  (t/testing "pure plain subtree, no layouts anywhere -> solve allowed"
    (let [root (uuid/next) r1 (uuid/next) r2 (uuid/next)
          objects {root (sm-shape root :frame uuid/zero [r1 r2])
                   r1   (sm-shape r1 :rect root)
                   r2   (sm-shape r2 :rect root)}]
      (t/is (false? (gm/skip-live-solve? [root] objects 100)))))

  (t/testing "subtree containing a grid descendant -> skip"
    (let [root (uuid/next) grid (uuid/next) c1 (uuid/next)
          objects {root (sm-shape root :frame uuid/zero [grid])
                   grid (sm-shape grid :frame root [c1] :grid)
                   c1   (sm-shape c1 :rect grid)}]
      (t/is (true? (gm/skip-live-solve? [root] objects 100)))))

  (t/testing "dragged shape is a DIRECT child of a grid (ancestor closure) -> skip"
    (let [grid (uuid/next) cell (uuid/next)
          objects {grid (sm-shape grid :frame uuid/zero [cell] :grid)
                   cell (sm-shape cell :rect grid)}]
      (t/is (true? (gm/skip-live-solve? [cell] objects 100)))))

  (t/testing "plain frame between shape and grid cuts the reflow walk -> solve allowed"
    (let [grid (uuid/next) plain (uuid/next) deep (uuid/next)
          objects {grid  (sm-shape grid :frame uuid/zero [plain] :grid)
                   plain (sm-shape plain :frame grid [deep])
                   deep  (sm-shape deep :rect plain)}]
      (t/is (false? (gm/skip-live-solve? [deep] objects 100)))))

  (t/testing "tree larger than max-nodes -> skip even without layouts"
    (let [root (uuid/next)
          kids (repeatedly 5 uuid/next)
          objects (into {root (sm-shape root :frame uuid/zero kids)}
                        (map (fn [id] [id (sm-shape id :rect root)]) kids))]
      (t/is (true?  (gm/skip-live-solve? [root] objects 5)))
      (t/is (false? (gm/skip-live-solve? [root] objects 100)))))

  (t/testing "multi-root: one root whose closure hits a layout flips the gesture"
    (let [proot (uuid/next) r1 (uuid/next)
          gparent (uuid/next) gchild (uuid/next)
          objects {proot  (sm-shape proot :frame uuid/zero [r1])
                   r1     (sm-shape r1 :rect proot)
                   gparent (sm-shape gparent :frame uuid/zero [gchild] :grid)
                   gchild (sm-shape gchild :rect gparent)}]
      (t/is (true? (gm/skip-live-solve? [proot gchild] objects 100))))))

