;; Benchmark v2: gm/set-objects-modifiers cost per resize frame, by subtree shape.
;; Run from common/: clojure -M:dev -e '(load-file "../tools/analysis/solve_resize_bench.clj")'
(ns solve-bench
  (:require
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

(defn add-frame [file label & params]
  (apply ths/add-sample-shape file label :type :frame :name "F" params))

(defn add-rect [file label parent-label & params]
  (apply ths/add-sample-shape file label :type :rect :name "R"
         :parent-label parent-label :width 50 :height 50 :x 0 :y 0
         :constraints-h :scale :constraints-v :scale params))

(defn add-grid [file label parent-label rows cols & params]
  (apply ths/add-sample-shape file label
         :type :frame :name "G" :parent-label parent-label
         :layout :grid :layout-grid-dir :row
         :layout-grid-columns (vec (repeat cols {:type :flex :value 1 :size 60.0 :max-size 1.0e9}))
         :layout-grid-rows (vec (repeat rows {:type :flex :value 1 :size 44.0 :max-size 1.0e9}))
         :layout-grid-cells {}
         :layout-padding-type :multiple
         :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
         :layout-gap {:column-gap 8 :row-gap 8}
         :width (* 60 cols) :height (* 44 rows)
         params))

(defn assign-cells
  "Assign grid cell positions like the app does after adding children."
  [file]
  (let [page   (thf/current-page file)
        page-id (:id page)
        objects (:objects page)
        grids  (keep (fn [s] (when (= :grid (:layout s)) (:id s))) (vals objects))]
    (update file :data
      (fn [fd]
        (reduce (fn [fd gid]
                  (update-in fd [:pages-index page-id :objects gid]
                             #(ctl/assign-cells % (get-in fd [:pages-index page-id :objects]))))
                fd grids)))))

(defn add-flex [file label parent-label & params]
  (apply ths/add-sample-shape file label
         :type :frame :name "FL" :parent-label parent-label
         :layout :flex :layout-flex-dir :row
         :layout-justify-content :start
         :layout-align-items :start
         :layout-align-content :start
         :layout-wrap-type :nowrap
         :layout-padding-type :multiple
         :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
         :layout-gap {:column-gap 8 :row-gap 8}
         :width 1200 :height 80
         params))

(defn page-objects [file] (:objects (thf/current-page file)))

(defn resize-modif-tree
  "Mirror start-resize: right-edge drag scaling x by 1.05, origin = selrect top-left."
  [objects id]
  (let [shape (get objects id)
        sr    (:selrect shape)]
    {id {:modifiers (ctm/resize (ctm/empty)
                                (gpt/point 1.05 1.0)
                                (gpt/point (:x1 sr) (:y1 sr))
                                (:transform shape)
                                (:transform-inverse shape))}}))

(defn bench [name file root-label]
  (let [objects (:objects (thf/current-page file))
        id      (thi/id root-label)
        mt      (resize-modif-tree objects id)
        result  (gm/set-objects-modifiers mt objects)
        n       (count result)
        iters   (if (< n 80) 200 30)
        _       (dotimes [_ 100] (gm/set-objects-modifiers mt objects)) ; JIT warmup
        t0      (System/nanoTime)
        _       (dotimes [_ iters] (gm/set-objects-modifiers mt objects))
        t1      (System/nanoTime)]
    (println (format "%-30s shapes=%-5d modif-entries=%-5d solve=%.2f ms/frame (jvm, %d iters)"
                     name (count objects) n (/ (- t1 t0) 1e6 iters) iters))))

(thi/reset-idmap!)

(println "== A. plain frame, no layout anywhere (rule would NOT skip -> pays solve) ==")
(let [f (add-frame (thf/sample-file :file1) :root :width 2000 :height 2000)]
  (bench "plain-100" (reduce #(add-rect %1 (keyword (str "r" %2)) :root) f (range 100)) :root))

(let [f (add-frame (thf/sample-file :file1) :root :width 4000 :height 4000)]
  (bench "plain-500" (reduce #(add-rect %1 (keyword (str "r" %2)) :root) f (range 500)) :root))

(let [f (add-frame (thf/sample-file :file1) :root :width 8000 :height 8000)]
  (bench "plain-2000" (reduce #(add-rect %1 (keyword (str "r" %2)) :root) f (range 2000)) :root))

(println "== B. plain root CONTAINING layout descendant (rule would NOT skip) ==")
(let [f (add-frame (thf/sample-file :file1) :root :width 2000 :height 2000)
      f (add-grid f :grid :root 10 10)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 100))]
  (bench "plain>grid10x10(100c)" (assign-cells f) :root))

(let [f (add-frame (thf/sample-file :file1) :root :width 3000 :height 3000)
      f (add-grid f :grid :root 20 20)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 400))]
  (bench "plain>grid20x20(400c)" (assign-cells f) :root))

(let [f (add-frame (thf/sample-file :file1) :root :width 2000 :height 2000)
      f (add-grid f :grid :root 10 10
                  :layout-item-h-sizing :auto
                  :layout-item-v-sizing :auto)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 100))]
  (bench "plain>grid10x10 AUTO" (assign-cells f) :root))

(println "== C. layout root resized directly (rule WOULD skip: fork behavior) ==")
(let [f (add-grid (thf/sample-file :file1) :grid nil 10 10)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 100))]
  (bench "grid10x10(100c) direct" (assign-cells f) :grid))

(let [f (add-grid (thf/sample-file :file1) :grid nil 20 20)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 400))]
  (bench "grid20x20(400c) direct" (assign-cells f) :grid))

(println "== D. controls ==")
(let [f (add-frame (thf/sample-file :file1) :root :width 500 :height 500)]
  (bench "frame alone" f :root))
(let [f (add-frame (thf/sample-file :file1) :root :width 500 :height 500)]
  (bench "frame+1 child" (add-rect f :r :root) :root))
(println "done")
