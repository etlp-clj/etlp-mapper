(ns etlp-mapper.ai-usage-logs
  "Database helpers for AI usage logging."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol AIUsageLogs
  (find-usage [db id]
    "Find a usage record by id.")
  (find-usage-for-org [db org-id]
    "List usage records for an organization.")
  (log-usage [db data]
    "Insert a new usage record."))

(extend-protocol AIUsageLogs
  duct.database.sql.Boundary
  (find-usage [{db :spec} id]
    (first (jdbc/query db ["select * from ai_usage_logs where id = ?" id])))
  (find-usage-for-org [{db :spec} org-id]
    (jdbc/query db ["select * from ai_usage_logs where org_id = ?" org-id]))
  (log-usage [{db :spec} data]
    (first (jdbc/insert! db :ai_usage_logs data))))

