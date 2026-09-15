;; Benchmark: MULTI-LEVEL NESTED / MIXED subtrees (grid+plain).
;; Flex cannot run on JVM: flex_layout init-layout-lines destructures nil
;; line-data on the first child and multiplies/adds it (num-children,
;; line-min-*) -- CLJS silently treats null as 0, the JVM NPEs. Flex nesting
;; is therefore unbenchable here; extrapolate from grid (same resolve-tree
;; re-solve structure).
;; Run from common/:
;;   clojure -M:dev -e '(load-file "../tools/analysis/solve_resize_bench3.clj")'
(ns solve-bench3
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

(defn add-grid [file label parent-label rows cols & params]
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
         (cond-> (vec params) (some? parent-label) (into [:parent-label parent-label]))))

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
        entries (count result)
        iters 50
        _ (dotimes [_ 100] (gm/set-objects-modifiers mt objects)) ; JIT warmup
        t0 (System/nanoTime)
        _ (dotimes [_ iters] (gm/set-objects-modifiers mt objects))
        t1 (System/nanoTime)]
    (println (format "%-42s shapes=%-5d tree=%-5d layouts=%-4d entries=%-5d solve=%.2f ms/frame (jvm)"
                     name (count objects) (count tree) layouts entries (/ (- t1 t0) 1e6 iters)))))

(defn affected-tree-has-layout? [ids objects]
  (boolean (some ctl/any-layout? (cgst/resolve-tree (set ids) objects))))

(thi/reset-idmap!)

(println "== N1. multiple SIBLING layouts under plain root (4x grid5x5 = 100 cells) ==")
(let [f (add-frame (thf/sample-file :file1) :root nil :width 3000 :height 3000)
      f (reduce (fn [f i] (add-grid f (keyword (str "g" i)) :root 5 5)) f (range 4))
      f (reduce (fn [f [i]]
                  (reduce #(add-rect %1 (keyword (str "c" i "-" %2)) (keyword (str "g" i))) f (range 25)))
                f (map vector (range 4)))]
  (bench "plain > 4x grid5x5 (100c, 4 layouts)" (assign-cells f) :root))

(println "== N2. NESTED layout-in-layout (grid6x6, each cell = grid2x2) ==")
(let [f (add-grid (thf/sample-file :file1) :root nil 6 6)
      f (reduce (fn [f i] (add-grid f (keyword (str "inner" i)) :root 2 2)) f (range 36))
      f (reduce (fn [f [i]]
                  (reduce #(add-rect %1 (keyword (str "d" i "-" %2)) (keyword (str "inner" i))) f (range 4)))
                f (map vector (range 36)))]
  (bench "grid6x6 > 36x grid2x2 (36 layouts)" (assign-cells f) :root))

(println "== N3. mixed depth: plain frame between layouts ==")
(let [f (add-frame (thf/sample-file :file1) :root nil :width 2000 :height 2000)
      f (add-grid f :grid :root 6 6)
      f (reduce (fn [f i] (add-frame f (keyword (str "card" i)) :grid :width 60 :height 44)) f (range 36))
      f (reduce (fn [f [i]]
                  (reduce #(add-rect %1 (keyword (str "e" i "-" %2)) (keyword (str "card" i))) f (range 10)))
                f (map vector (range 36)))]
  (bench "plain > grid6x6 > 36x plain(10c)" (assign-cells f) :root))

(println "== N4. deep plain chain, no layout (depth effect) ==")
(let [f (add-frame (thf/sample-file :file1) :a nil :width 2000 :height 2000)
      f (add-frame f :b :a :width 1800 :height 1800)
      f (add-frame f :c :b :width 1600 :height 1600)
      f (add-frame f :d :c :width 1400 :height 1400)
      f (reduce #(add-rect %1 (keyword (str "r" %2)) :d) f (range 100))]
  (bench "plain>plain>plain>plain>100 rects" f :a))

(println "== N5. auto-sized nested grids (sizing-auto cascade) ==")
(let [f (add-grid (thf/sample-file :file1) :root nil 6 6)
      f (reduce (fn [f i] (add-grid f (keyword (str "inner" i)) :root 2 2
                                    :layout-item-h-sizing :auto
                                    :layout-item-v-sizing :auto))
                f (range 36))
      f (reduce (fn [f [i]]
                  (reduce #(add-rect %1 (keyword (str "d" i "-" %2)) (keyword (str "inner" i))) f (range 4)))
                f (map vector (range 36)))]
  (bench "grid6x6 > 36x grid2x2 AUTO" (assign-cells f) :root))

(println "== N6. resize a leaf INSIDE nested grid (ancestor walk stops at inner grid) ==")
(let [f (add-grid (thf/sample-file :file1) :root nil 6 6)
      f (add-grid f :inner :root 2 2)
      f (add-rect f :leaf :inner)
      f (reduce #(add-rect %1 (keyword (str "c" %2)) :root) f (range 35))
      f (reduce #(add-rect %1 (keyword (str "d" %2)) :inner) f (range 3))
      f (assign-cells f)]
  (bench "grid6x6 > grid2x2 > rect, resize rect" f :leaf))

(println "== references from bench1 (same machine, warmed) ==")
(println "plain>grid10x10: 1.83ms/103sh | plain>grid20x20: 4.13ms/403sh | plain-100: 0.56ms")

(println "== check cost on nested tree ==")
(let [f (add-grid (thf/sample-file :file1) :root nil 6 6)
      f (reduce (fn [f i] (add-grid f (keyword (str "inner" i)) :root 2 2)) f (range 36))
      f (reduce (fn [f [i]]
                  (reduce #(add-rect %1 (keyword (str "d" i "-" %2)) (keyword (str "inner" i))) f (range 4)))
                f (map vector (range 36)))
      f (assign-cells f)
      objects (:objects (thf/current-page f))
      root-id (thi/id :root)
      iters 100
      t0 (System/nanoTime)
      _ (dotimes [_ iters] (affected-tree-has-layout? [root-id] objects))
      t1 (System/nanoTime)]
  (println (format "has-layout? on N2 nested tree (%d shapes) = %.3f ms/check (jvm)"
                   (count objects) (/ (- t1 t0) 1e6 iters))))
(println "done")
