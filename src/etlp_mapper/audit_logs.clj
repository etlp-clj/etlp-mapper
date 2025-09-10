(ns etlp-mapper.audit-logs
  "Database helpers for storing audit log entries."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol AuditLogs
  (find-log [db id]
    "Find a log entry by id.")
  (find-logs [db org-id]
    "List log entries for an organization.")
  (create-log [db data]
    "Insert a new audit log entry."))

(extend-protocol AuditLogs
  duct.database.sql.Boundary
  (find-log [{db :spec} id]
    (first (jdbc/query db ["select * from audit_logs where id = ?" id])))
  (find-logs [{db :spec} org-id]
    (jdbc/query db ["select * from audit_logs where org_id = ? order by created_at desc" org-id]))
  (create-log [{db :spec} data]
    (first (jdbc/insert! db :audit_logs data))))

