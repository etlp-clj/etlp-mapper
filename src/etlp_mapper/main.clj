(ns etlp-mapper.main
  (:gen-class)
  (:require [clojure.string :as str]
            [duct.core :as duct]
            [integrant.core :as ig]
            [etlp-mapper.pgtypes]
            [etlp-mapper.auth-component]
            [etlp-mapper.middlewares]
            [etlp-mapper.handler.copilot]))

(duct/load-hierarchy)

(defn- sqlite-backend?
  "True when the service should use the SQLite backend. Driven by JDBC_URL
   (jdbc:sqlite:...) or an explicit DB_DIALECT=sqlite override."
  []
  (or (= "sqlite" (some-> (System/getenv "DB_DIALECT") str/lower-case))
      (some-> (System/getenv "JDBC_URL") str/lower-case (str/starts-with? "jdbc:sqlite"))))

;; SQLite migration set, defined inertly in config.edn's :duct.profile/base.
;; We swap the active ragtime list to these at runtime rather than via a Duct
;; profile, so the build-time compiler only ever sees the Postgres refs (a
;; :duct.profile/sqlite key gets auto-merged by lein-duct, which drops its
;; composite-keyed defs and leaves the refs dangling at compile).
(def ^:private sqlite-migrations
  [(ig/ref :etlp-mapper.migration-sqlite/create-mappings)
   (ig/ref :etlp-mapper.migration-sqlite/create-mappings-history)
   (ig/ref :etlp-mapper.migration-sqlite/history-trigger)
   (ig/ref :etlp-mapper.migration-sqlite/updated-at-trigger)])

(defn -main [& args]
  (let [keys    (or (duct/parse-keys args) [:duct/daemon])
        sqlite? (sqlite-backend?)
        config  (cond-> (duct/read-config (duct/resource "etlp_mapper/config.edn"))
                  sqlite? (assoc-in [:duct.profile/base :duct.migrator/ragtime :migrations]
                                    sqlite-migrations))]
    ;; Load the JDBC type-marshalling for the active dialect. Both pgtypes and
    ;; sqlitetypes extend clojure.java.jdbc protocols globally; sqlitetypes is
    ;; required after pgtypes so its map/vector/number handlers win for SQLite.
    (when sqlite?
      (require 'etlp-mapper.sqlitetypes))
    (duct/exec-config config [:duct.profile/prod :duct.profile/dev] keys)
    (System/exit 0)))
