(ns etlp-mapper.integration.database-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]))

(def required-tables
  #{"organizations"
    "users"
    "organization_members"
    "organization_invites"
    "organization_subscriptions"
    "audit_logs"
    "ai_usage_logs"
    "mappings"
    "mappings_history"})

(defn db-spec []
  (some-> (System/getenv "JDBC_URL")
          (hash-map :connection-uri)))

(defn normalized-table-names [spec]
  (->> (jdbc/query spec ["select table_name from information_schema.tables where table_schema = 'public'"])
       (map (comp str/lower-case :table_name))
       set))

(deftest ^:integration migrations-create-core-tables
  (if-let [spec (db-spec)]
    (let [tables (normalized-table-names spec)]
      (is (set/subset? required-tables tables)
          (str "Missing tables: " (pr-str (set/difference required-tables tables)))))
    (do
      (println "Skipping migrations-create-core-tables integration test; JDBC_URL not set.")
      (is true))))
