(ns etlp-mapper.handler.orgs
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.identity :as identity]
            [etlp-mapper.onboarding :as onboarding]))

;; Handler for creating a new organization. Requires an authenticated user
;; without an active organisation association. The real implementation would
;; create the org, membership, subscription and audit log entries. Here we
;; simply generate a UUID for the new organisation and return it.

(defmethod ig/init-key :etlp-mapper.handler.orgs/create
  [_ {:keys [db kc]}]
  (fn [request]
    (let [existing-org (identity/org-id request)
          {:keys [name]} (:body-params request)
          user          (identity/user request)
          user-id       (identity/user-id request)]
      (cond
        existing-org
        [::response/forbidden {:error "Organization already selected"}]

        (nil? user-id)
        [::response/forbidden {:error "User context required"}]

        (not (seq name))
        [::response/bad-request {:error "Organization name required"}]

        :else
        (let [org-id (onboarding/ensure-org! (or (:spec db) db)
                                             kc
                                             {:name name
                                              :user {:id (:id user)
                                                     :email (:email user)
                                                     :idp-sub (:idp-sub user)
                                                     :name (:name user)}})]
          (audit-logs/log! db {:org-id org-id
                               :user-id user-id
                               :action "create-organization"})
          (ai-usage-logs/log! db {:org-id org-id
                                  :user-id user-id
                                  :feature-type "onboarding"
                                  :input-tokens 0
                                  :output-tokens 0})
          [::response/ok {:org_id org-id}])))))
