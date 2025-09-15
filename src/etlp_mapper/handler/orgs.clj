(ns etlp-mapper.handler.orgs
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]))

;; Handler for creating a new organization. Requires an authenticated user
;; without an active organisation association. The real implementation would
;; create the org, membership, subscription and audit log entries. Here we
;; simply generate a UUID for the new organisation and return it.

(defmethod ig/init-key :etlp-mapper.handler.orgs/create
  [_ {:keys [db]}]
  (fn [request]
    (if (identity/org-id request)
      [::response/forbidden {:error "Organization already selected"}]
      (let [new-id (str (java.util.UUID/randomUUID))
            user-id (get-in request [:identity :claims :sub])]
        (audit-logs/log! db {:org-id new-id
                             :user-id user-id
                             :action "create-organization"})
        (ai-usage-logs/log! db {:org-id new-id
                                :user-id user-id
                                :feature-type "onboarding"
                                :input-tokens 0
                                :output-tokens 0})
        [::response/ok {:org_id new-id}]))))
