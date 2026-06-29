(ns etlp-mapper.sqlitetypes
  "SQLite JDBC marshalling for the `content` column.

   SQLite has no native JSON/JSONB type, so `content` is a TEXT column holding
   a JSON string. This namespace is the SQLite analogue of `etlp-mapper.pgtypes`:

   - WRITE: Clojure maps/vectors are serialised to a JSON string before they
     reach the prepared statement (the Postgres path used a `jsonb` PGobject
     keyed off `getParameterTypeName`, which the SQLite driver does not expose).
   - READ: the `content` column (and only that column) is parsed back from JSON
     TEXT into Clojure data, matching what `pgtypes` returned for `:jsonb`.

   It also re-binds `java.lang.Number` to a plain `setObject`, because `pgtypes`
   overrode it to call `getParameterMetaData`, which the SQLite driver does not
   support. Loading order matters: `etlp-mapper.main` requires this namespace
   AFTER `etlp-mapper.pgtypes`, so these handlers win when SQLite is active."
  (:require [clojure.java.jdbc :as jdbc]
            [cheshire.core :as json]
            [duct.database.sql]
            [duct.handler.sql])
  (:import [java.sql PreparedStatement ResultSetMetaData]
           [duct.database.sql Boundary]))

;; --- write side: Clojure collections -> JSON TEXT ---

(extend-protocol jdbc/ISQLParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s ^long i]
    (.setString s i (json/generate-string m)))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s ^long i]
    (.setString s i (json/generate-string v)))

  ;; Neutralise pgtypes' metadata-driven Number handler (SQLite JDBC has no
  ;; usable ParameterMetaData); plain setObject is what we want for ints/longs.
  java.lang.Number
  (set-parameter [n ^PreparedStatement s ^long i]
    (.setObject s i n)))

;; --- read side: decode the `content` column from JSON TEXT ---

(extend-protocol jdbc/IResultSetReadColumn
  java.lang.String
  (result-set-read-column [val ^ResultSetMetaData rsmeta ^long idx]
    (if (= "content" (.getColumnLabel rsmeta idx))
      (when (seq val) (json/parse-string val))
      val)))

;; --- generated-key fix for INSERT handlers ---
;; duct.handler.sql/insert! uses jdbc/db-do-prepared-return-keys, whose key map
;; is {:id N} on Postgres but {(keyword "last_insert_rowid()") N} on SQLite
;; (Xerial). The `:location "mappings/{id}"` template then can't resolve {id},
;; so create responses come back with an empty Location header (and the CDC
;; webhook can't extract the new id). Re-bind insert! so the returned map always
;; carries :id. extend replaces the whole per-type method map, so query/execute!
;; are re-stated identically to duct.handler.sql's defaults. SQLite-only: this
;; namespace is loaded only when the SQLite backend is active.
(extend-protocol duct.handler.sql/RelationalDatabase
  Boundary
  (query    [{:keys [spec]} sql] (jdbc/query spec sql))
  (execute! [{:keys [spec]} sql] (jdbc/execute! spec sql))
  (insert!  [{:keys [spec]} sql]
    (let [m (jdbc/db-do-prepared-return-keys spec sql)]
      (assoc m :id (or (:id m)
                       (get m (keyword "last_insert_rowid()"))
                       (val (first m)))))))
