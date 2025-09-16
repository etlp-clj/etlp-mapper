(ns etlp-mapper.validation.migration-auditor-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def config
  (->> "etlp_mapper/config.edn"
       io/resource
       slurp
       (edn/read-string {:readers {'ig/ref (fn [v] v)
                                   'duct/include (fn [v] v)}})))

(defn migration-map []
  (:duct.profile/base config))

(defn migration-entries []
  (let [base (migration-map)]
    (for [[k v] base
          :when (and (vector? k)
                     (= :duct.migrator.ragtime/sql (first k)))]
      [k v])))

(deftest single-statement-migrations
  (doseq [[_ {:keys [up down]}] (migration-entries)]
    (is (= 1 (count up)) "Each migration :up should contain one SQL statement string")
    (is (= 1 (count down)) "Each migration :down should contain one SQL statement string")
    (doseq [sql (concat up down)]
      (is (string? sql)))))

(deftest function-migrations-include-language
  (doseq [[_ {:keys [up]}] (migration-entries)]
    (when (some #(re-find #"CREATE OR REPLACE FUNCTION" %) up)
      (is (some #(re-find #"LANGUAGE plpgsql" (str/upper-case %)) up)))))
