(ns etlp-mapper.service.audit-logs
  (:require [clojure.java.jdbc :as jdbc]))

(defn log!
  "Insert a new audit log entry into the audit_logs table.
  Expects a database boundary with :spec and an entry map containing
  :org-id, :user-id, :action, and optional :context.
  Context will be stored as a stringified representation."
  [{:keys [spec]} {:keys [org-id user-id action context]}]
  (jdbc/insert! spec :audit_logs
                {:org_id org-id
                 :user_id user-id
                 :action action
                 :context (when context (pr-str context))}))
