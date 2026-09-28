;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.shape-tree-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; NOTE: these tests encode the clip-aware drop-target semantics: a frame
;; region hidden by an ancestor clip-content (or by a mask, inside masked
;; groups) can never be a placement target; the point resolves to the
;; top-most visible frame instead, falling back to the root board.

(t/deftest test-top-nested-frame-drop-on-clipped-away-region-goes-to-root
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-frame :clipped-frame
                                   :x 0 :y 0 :width 100 :height 100
                                   :show-content false)
                    (tho/add-frame :inner-frame
                                   :parent-label :clipped-frame
                                   :x 150 :y 150 :width 50 :height 50))

        objects (-> file thf/current-page :objects)]

    ;; ==== Check
    ;; the drop point is over the overflowed (clipped-away, invisible)
    ;; region of :inner-frame, so it must resolve to the root board
    (t/is (= uuid/zero
             (ctst/top-nested-frame objects (gpt/point 160 160) nil false)))))

(t/deftest test-top-nested-frame-topmost-visible-frame-wins
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-frame :under-frame
                                   :x 100 :y 100 :width 200 :height 200)
                    (tho/add-frame :clipped-frame
                                   :x 0 :y 0 :width 200 :height 200
                                   :show-content false)
                    (tho/add-frame :inner-frame
                                   :parent-label :clipped-frame
                                   :x 250 :y 250 :width 50 :height 50))

        objects (-> file thf/current-page :objects)]

    ;; ==== Check
    ;; :inner-frame covers the point but is clipped away there, so the
    ;; next visible frame in z-order (:under-frame) becomes the target
    (t/is (= (thi/id :under-frame)
             (ctst/top-nested-frame objects (gpt/point 260 260) nil false)))))

(t/deftest test-top-nested-frame-visible-region-of-clipped-frame
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-frame :clipped-frame
                                   :x 0 :y 0 :width 100 :height 100
                                   :show-content false)
                    (tho/add-frame :inner-frame
                                   :parent-label :clipped-frame
                                   :x 20 :y 20 :width 60 :height 60))

        objects (-> file thf/current-page :objects)]

    ;; ==== Check
    ;; guard against over-filtering: within the visible region of the
    ;; clipped frame, nesting resolution behaves exactly as before
    (t/is (= (thi/id :inner-frame)
             (ctst/top-nested-frame objects (gpt/point 50 50) nil false)))
    (t/is (= (thi/id :clipped-frame)
             (ctst/top-nested-frame objects (gpt/point 10 10) nil false)))))

(t/deftest test-top-nested-frame-masked-group-region
  (let [;; ==== Setup
        ;; in a masked group the first child shape is the mask
        file    (-> (thf/sample-file :file1)
                    (tho/add-group :mask-group :masked-group true)
                    (tho/add-rect :mask-shape
                                  :parent-label :mask-group
                                  :x 0 :y 0 :width 50 :height 50)
                    (tho/add-frame :masked-frame
                                   :parent-label :mask-group
                                   :x 25 :y 25 :width 100 :height 100))

        objects (-> file thf/current-page :objects)]

    ;; ==== Check
    ;; the region of :masked-frame outside the mask is invisible: it
    ;; cannot be a placement target and the point goes to the root board
    (t/is (= uuid/zero
             (ctst/top-nested-frame objects (gpt/point 100 100) nil false)))
    ;; inside the mask the frame is still the target
    (t/is (= (thi/id :masked-frame)
             (ctst/top-nested-frame objects (gpt/point 30 30) nil false)))))

(t/deftest test-top-nested-frame-hidden-and-excluded-still-filtered
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-frame :hidden-frame
                                   :x 0 :y 0 :width 100 :height 100
                                   :hidden true)
                    (tho/add-frame :frame-a
                                   :x 200 :y 0 :width 200 :height 200)
                    (tho/add-frame :frame-b
                                   :x 250 :y 50 :width 100 :height 100))

        objects (-> file thf/current-page :objects)]

    ;; ==== Check
    ;; hidden frames are never placement targets, even at their center
    (t/is (= uuid/zero
             (ctst/top-nested-frame objects (gpt/point 50 50) nil false)))
    ;; the excluded set keeps filtering candidates, and the remaining
    ;; visible frame wins, in both exclusion directions
    (t/is (= (thi/id :frame-a)
             (ctst/top-nested-frame objects (gpt/point 300 100)
                                    #{(thi/id :frame-b)} false)))
    (t/is (= (thi/id :frame-b)
             (ctst/top-nested-frame objects (gpt/point 300 100)
                                    #{(thi/id :frame-a)} false)))))

(t/deftest test-get-frame-id-by-position-clip-aware
  (let [;; ==== Setup
        file    (-> (thf/sample-file :file1)
                    (tho/add-frame :clipped-frame
                                   :x 0 :y 0 :width 100 :height 100
                                   :show-content false)
                    (tho/add-frame :inner-frame
                                   :parent-label :clipped-frame
                                   :x 150 :y 150 :width 50 :height 50))

        objects (-> file thf/current-page :objects)]

    ;; ==== Check
    ;; sidebar component drop-in path: same clip-aware semantics as
    ;; top-nested-frame, falling back to the root board
    ;;
    ;; NOTE: an explicit always-true validator is passed because the default
    ;; one in get-frame-by-position (`#(-> true)`, a zero-arg fn) throws an
    ;; ArityException on the JVM when invoked with the shape argument; this
    ;; is a pre-existing issue unrelated to the clip-aware behavior.
    (t/is (= uuid/zero
             (ctst/get-frame-id-by-position objects (gpt/point 160 160)
                                            {:validator (constantly true)})))
    ;; within the visible region of the clipped frame nothing changes
    (t/is (= (thi/id :clipped-frame)
             (ctst/get-frame-id-by-position objects (gpt/point 50 50)
                                            {:validator (constantly true)})))))
