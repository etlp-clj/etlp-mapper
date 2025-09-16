(ns etlp-mapper.kondo
  (:require [clj-kondo.hooks-api :as api]
            [clojure.string :as str]))

(defn jdbc-query
  "Warn when jdbc/query is invoked with a SQL string missing an organization_id predicate."
  [{:keys [node]}]
  (let [[_ _ sql-expr & _] (:children node)
        sql-sexpr (when sql-expr (api/sexpr sql-expr))
        sql-text (when (and (vector? sql-sexpr)
                             (string? (first sql-sexpr)))
                   (first sql-sexpr))]
    (when (and sql-text
               (str/includes? (str/lower-case sql-text) "select")
               (not (re-find #"organization_id" sql-text)))
      (api/reg-finding! {:message "jdbc/query missing organization_id predicate"
                         :type :etlp/org-scope
                         :row (:row sql-expr)
                         :col (:col sql-expr)}))
    {:node node}))
