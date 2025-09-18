(ns etlp-mapper.validation.org-scope-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def skip-files
  #{"src/etlp_mapper/users.clj"
    "src/etlp_mapper/organizations.clj"
    "src/etlp_mapper/onboarding.clj"})

(defn clj-files []
  (for [f (file-seq (io/file "src/etlp_mapper"))
        :when (and (.isFile f)
                   (str/ends-with? (.getName f) ".clj"))]
    (.getPath f)))

(deftest jdbc-queries-are-org-scoped
  (doseq [file (clj-files)
          :when (not (skip-files file))]
    (let [content (slurp file)]
      (doseq [[_ sql] (re-seq #"jdbc/query[^\[]*\[\s*\"([^\"]+)\"" content)]
        (let [upper (str/upper-case sql)]
          (when (and (str/includes? upper "SELECT")
                     (not (re-find #"ORGANIZATION_ID" upper)))
            (is false (str file " missing organization_id in query: " sql))))))))
