(ns etlp-mapper.audit-logs
  "Database helpers for storing audit log entries."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol AuditLogs
  (find-log [db org-id id]
    "Find a log entry by id scoped to an organization.")
  (find-logs [db org-id]
    "List log entries for an organization.")
  (create-log [db data]
    "Insert a new audit log entry."))

(defn- find-log* [db org-id id]
  (first (jdbc/query db
                     ["select * from audit_logs where id = ? and organization_id = ?" id org-id])))

(defn- find-logs* [db org-id]
  (jdbc/query db ["select * from audit_logs where organization_id = ? order by created_at desc" org-id]))

(defn- create-log* [db data]
  (first (jdbc/insert! db :audit_logs data)))

(extend-protocol AuditLogs
  duct.database.sql.Boundary
  (find-log [{db :spec} org-id id]
    (find-log* db org-id id))
  (find-logs [{db :spec} org-id]
    (find-logs* db org-id))
  (create-log [{db :spec} data]
    (create-log* db data)))


(defn log!
  "Insert a new audit log entry into the audit_logs table. Accepts a database
  boundary and a map with :org-id, :user-id, :action and optional :context."
  [db {:keys [org-id user-id action context]}]
  (create-log db {:organization_id org-id
                  :user_id user-id
                  :action action
                  :context (when context (pr-str context))}))
