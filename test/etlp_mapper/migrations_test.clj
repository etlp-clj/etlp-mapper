(ns etlp-mapper.migrations-test
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defonce config
  (->> "etlp_mapper/config.edn"
       io/resource
       slurp
       (edn/read-string {:readers {'ig/ref (fn [v] v)
                                   'duct/include (fn [v] v)}})))

(deftest migrations-include-mappings-tables
  (let [mig-ids (set (get-in config [:duct.profile/base :duct.migrator/ragtime :migrations]))]
    (is (contains? mig-ids :etlp-mapper.migration/create-mappings))
    (is (contains? mig-ids :etlp-mapper.migration/create-mappings-history))))

(deftest migrations-enforce-org-id
  (let [base (:duct.profile/base config)
        sql1 (get base [:duct.migrator.ragtime/sql :etlp-mapper.migration/create-mappings])
        sql2 (get base [:duct.migrator.ragtime/sql :etlp-mapper.migration/create-mappings-history])
        up1 (:up sql1)
        up2 (:up sql2)]
    (is (some #(re-find #"organization_id UUID NOT NULL" %) up1))
    (is (some #(re-find #"organization_id UUID NOT NULL" %) up2))))

