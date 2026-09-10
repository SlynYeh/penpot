;; Benchmark: ancestor-hole scenarios + skip-condition check cost.
;; Complements solve_resize_bench.clj: what a resize pays per frame when the
;; dragged shape is a DIRECT child of a layout (its own subtree has none) --
;; the solve's resolve-tree walks up (get-reflow-root) and re-solves the whole
;; layout ancestor. A plain frame between shape and layout CUTS the walk.
;; Run from common/: clojure -M:dev -e '(load-file "../tools/analysis/solve_resize_bench2.clj")'
(ns solve-bench2
  (:require
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.tree-seq :as cgst]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]))

(defn add-frame [file label parent-label & params]
  (apply ths/add-sample-shape file label :type :frame :name "F"
         (cond-> (vec params) (some? parent-label) (into [:parent-label parent-label]))))

(defn add-rect [file label parent-label & params]
  (apply ths/add-sample-shape file label :type :rect :name "R"
         :parent-label parent-label :width 50 :height 50 :x 0 :y 0
         :constraints-h :scale :constraints-v :scale params))

(defn add-grid [file label rows cols & params]
  (apply ths/add-sample-shape file label
         :type :frame :name "G"
         :layout :grid :layout-grid-dir :row
         :layout-grid-columns (vec (repeat cols {:type :flex :value 1 :size 60.0 :max-size 1.0e9}))
         :layout-grid-rows (vec (repeat rows {:type :flex :value 1 :size 44.0 :max-size 1.0e9}))
         :layout-grid-cells {}
         :layout-padding-type :multiple
         :layout-padding {:p1 0 :p2 0 :p3 0 :p4 0}
         :layout-gap {:column-gap 8 :row-gap 8}
         :width (* 60 cols) :height (* 44 rows)
         params))

(defn assign-cells [file]
  (let [page (thf/current-page file)
        page-id (:id page)
        grids (keep (fn [s] (when (= :grid (:layout s)) (:id s)))
                    (vals (:objects page)))]
    (update file :data
      (fn [fd]
        (reduce (fn [fd gid]
                  (update-in fd [:pages-index page-id :objects gid]
                             #(ctl/assign-cells % (get-in fd [:pages-index page-id :objects]))))
                fd grids)))))

(defn resize-modif-tree [objects id]
  (let [shape (get objects id)
        sr (:selrect shape)]
    {id {:modifiers (ctm/resize (ctm/empty) (gpt/point 1.05 1.0)
                                (gpt/point (:x1 sr) (:y1 sr))
                                (:transform shape) (:transform-inverse shape))}}))

(defn bench [name file root-label]
  (let [objects (:objects (thf/current-page file))
        id (thi/id root-label)
        mt (resize-modif-tree objects id)
        result (gm/set-objects-modifiers mt objects)
        tree (cgst/resolve-tree #{id} objects)
        layouts (count (filter ctl/any-layout? tree))
        iters 50
        _ (dotimes [_ 100] (gm/set-objects-modifiers mt objects)) ; JIT warmup
        t0 (System/nanoTime)
        _ (dotimes [_ iters] (gm/set-objects-modifiers mt objects))
        t1 (System/nanoTime)]
    (println (format "%-40s shapes=%-5d solve-tree=%-5d layouts=%-3d solve=%.2f ms/frame (jvm)"
                     name (count objects) (count tree) layouts (/ (- t1 t0) 1e6 iters)))))

(defn affected-tree-has-layout? [ids objects]
  (boolean (some ctl/any-layout? (cgst/resolve-tree (set ids) objects))))

(thi/reset-idmap!)

(println "== ANCESTOR HOLE: direct layout child (subtree itself has NO layout) ==")
;; FULL grid 10x10: 99 plain cells + 1 cell = plain frame with 10 rect children.
(let [f (add-grid (thf/sample-file :file1) :grid 10 10)
      f (add-frame f :inner :grid :width 60 :height 44)
      f (reduce #(add-rect %1 (keyword (str "r" %2)) :inner) f (range 10))
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 99))
      f (assign-cells f)]
  (bench "grid10x10-full > plain(10c), resize plain" f :inner))

;; FULL grid 20x20, resize ONE direct rect child (icon-in-cell case)
(let [f (add-grid (thf/sample-file :file1) :grid 20 20)
      f (add-rect f :cellrect :grid)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 399))
      f (assign-cells f)]
  (bench "grid20x20-full > rect, resize the rect" f :cellrect))

;; same as above but resized shape sits inside a PLAIN frame inside the grid
;; (plain frame cuts the upward reflow walk)
(let [f (add-grid (thf/sample-file :file1) :grid 10 10)
      f (add-frame f :inner :grid :width 60 :height 44)
      f (add-rect f :deep :inner)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 99))
      f (assign-cells f)]
  (bench "grid10x10-full > plain > rect, resize rect" f :deep))

(println "== reference ==")
(let [f (add-grid (thf/sample-file :file1) :grid 10 10)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :grid) f (range 100))
      f (assign-cells f)]
  (bench "grid10x10-full direct resize (skipped)" f :grid))

(let [f (add-frame (thf/sample-file :file1) :root nil :width 2000 :height 2000)
      f (reduce #(add-rect %1 (keyword (str "r" %2)) :root) f (range 100))]
  (bench "plain-100 (no layout anywhere)" f :root))

(println "== skip-condition check cost (once per gesture) ==")
(let [f (add-frame (thf/sample-file :file1) :root nil :width 8000 :height 8000)
      f (reduce #(add-rect %1 (keyword (str "r" %2)) :root) f (range 2000))
      objects (:objects (thf/current-page f))
      root-id (thi/id :root)
      iters 200
      t0 (System/nanoTime)
      _ (dotimes [_ iters] (affected-tree-has-layout? [root-id] objects))
      t1 (System/nanoTime)]
  (println (format "has-layout? on 2002-shape subtree = %.3f ms/check (jvm)" (/ (- t1 t0) 1e6 iters))))
(println "done")
