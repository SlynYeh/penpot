(ns build
  (:refer-clojure :exclude [compile])
  (:require
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [clojure.java.io])
  (:import
   [java.io File]
   [java.util.jar JarFile]))

(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file "target/penpot.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn- extract-jar!
  "Extract a JAR file into target directory, merging META-INF/services/ files."
  [jar-path target-dir]
  (with-open [jar (JarFile. (File. jar-path))]
    (doseq [entry (enumeration-seq (.entries jar))]
      (let [name (.getName entry)]
        (when-not (.isDirectory entry)
          (cond
            (.startsWith name "META-INF/services/")
            (let [f (File. target-dir name)
                  new-content (slurp (.getInputStream jar entry))]
              (.mkdirs (.getParentFile f))
              (if (.exists f)
                (let [existing (slurp f)
                      existing-lines (str/split-lines existing)
                      new-lines (str/split-lines new-content)]
                  (spit f (str/join "\n" (distinct (concat existing-lines new-lines)))))
                (spit f new-content)))

            (not (.startsWith name "META-INF/"))
            (let [f (File. target-dir name)]
              (.mkdirs (.getParentFile f))
              (with-open [in (.getInputStream jar entry)]
                (clojure.java.io/copy in f)))))))))

(defn jar [_]
  (b/copy-dir
   {:src-dirs ["src" "resources"]
    :target-dir class-dir})

  ;; Extract the local GaussDB JDBC driver JAR into classes
  (extract-jar! "lib/opengaussjdbc.jar" class-dir)

  (b/uber
   {:class-dir class-dir
    :uber-file jar-file
    :main 'clojure.main
    :exclude [#".*Log4j2Plugins\.dat$"]
    :basis basis}))

(defn compile [_]
  (b/javac
   {:src-dirs ["dev/java"]
    :class-dir class-dir
    :basis basis
    :javac-opts ["-source" "17" "-target" "17"]}))
