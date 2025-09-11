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
        sql1 (get base [:duct.migrator.ragtime/sql :etlp-mapper.migration/add-org-id-to-mappings])
        sql2 (get base [:duct.migrator.ragtime/sql :etlp-mapper.migration/add-org-id-to-mappings-history])
        up1 (:up sql1)
        up2 (:up sql2)]
    (is (some #(re-find #"ALTER TABLE mappings ADD COLUMN org_id" %) up1))
    (is (some #(re-find #"ALTER TABLE mappings ALTER COLUMN org_id SET NOT NULL" %) up1))
    (is (some #(re-find #"ALTER TABLE mappings_history ADD COLUMN org_id" %) up2))
    (is (some #(re-find #"ALTER TABLE mappings_history ALTER COLUMN org_id SET NOT NULL" %) up2))))

