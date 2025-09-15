(ns etlp-mapper.ai-usage-logs
  "Database helpers for AI usage logging."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol AIUsageLogs
  (find-usage [db org-id id]
    "Find a usage record by id scoped to an organization.")
  (find-usage-for-org [db org-id]
    "List usage records for an organization.")
  (log-usage [db data]
    "Insert a new usage record."))

(extend-protocol AIUsageLogs
  duct.database.sql.Boundary
  (find-usage [{db :spec} org-id id]
    (first (jdbc/query db
                       ["select * from ai_usage_logs where id = ? and organization_id = ?" id org-id])))
  (find-usage-for-org [{db :spec} org-id]
    (jdbc/query db ["select * from ai_usage_logs where organization_id = ?" org-id]))
  (log-usage [{db :spec} data]
    (first (jdbc/insert! db :ai_usage_logs data))))


(defn log!
  "Insert a new AI usage log entry. Accepts a database boundary and a map with
  :org-id, :user-id, :feature-type, :input-tokens and :output-tokens."
  [db {:keys [org-id user-id feature-type input-tokens output-tokens]}]
  (log-usage db {:organization_id org-id
                 :user_id user-id
                 :feature_type feature-type
                 :input_tokens input-tokens
                 :output_tokens output-tokens}))

