(ns etlp-mapper.service.ai-usage-logs
  (:require [clojure.java.jdbc :as jdbc]))

(defn log!
  "Insert a new AI usage log entry into the ai_usage_logs table.
  Expects a database boundary with :spec and an entry map containing
  :org-id, :user-id, :feature-type and optional :tokens."
  [{:keys [spec]} {:keys [org-id user-id feature-type tokens]}]
  (jdbc/insert! spec :ai_usage_logs
                {:org_id org-id
                 :user_id user-id
                 :feature_type feature-type
                 :tokens tokens}))
