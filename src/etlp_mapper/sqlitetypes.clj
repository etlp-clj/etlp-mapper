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
            [cheshire.core :as json])
  (:import [java.sql PreparedStatement ResultSetMetaData]))

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
