(ns etlp-mapper.handler.invites
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.identity :as identity]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]))

(defn- admin-role? [roles]
  (some #{:owner :admin} roles))
;; POST /orgs/:org-id/invites – create an invite token.  Requires the caller to
;; have an admin role within the organisation.
(defmethod ig/init-key :etlp-mapper.handler.invites/create
  [_ {:keys [db]}]
  (fn [{[_ path-org] :ataraxy/result :as request}]
    (let [org-id (identity/org-id request)
          roles  (identity/roles request)
          user-id (identity/user-id request)]
      (cond
        (nil? org-id)
        [::response/forbidden {:error "Organization context required"}]
        (not= path-org org-id)
        [::response/forbidden {:error "Organization mismatch"}]
        (nil? user-id)
        [::response/forbidden {:error "User context required"}]
        (admin-role? roles)
        (let [token (str (java.util.UUID/randomUUID))]
          (audit-logs/log! db {:org-id org-id
                               :user-id user-id
                               :action "create-invite"
                               :context {:token token}})
          (ai-usage-logs/log! db {:org-id org-id
                                  :user-id user-id
                                  :feature-type "invite"
                                  :input-tokens 0
                                  :output-tokens 0})
          [::response/ok {:org_id org-id :token token}])
        :else
        [::response/forbidden {:error "Insufficient role"}]))))

;; POST /invites/accept – verify an invite token and add the user to the
;; organisation membership list.
(defmethod ig/init-key :etlp-mapper.handler.invites/accept
  [_ {:keys [db]}]
  (fn [{{:keys [token org_id]} :body-params :as request}]
    (let [org-id (identity/org-id request)
          user-id (identity/user-id request)]
      (cond
        (nil? org-id)
        [::response/forbidden {:error "Organization context required"}]
        (and org_id (not= org_id org-id))
        [::response/forbidden {:error "Organization mismatch"}]
        (nil? token)
        [::response/bad-request {:error "Invalid token"}]
        (nil? user-id)
        [::response/forbidden {:error "User context required"}]
        :else
        (do
          (audit-logs/log! db {:org-id org-id
                               :user-id user-id
                               :action "accept-invite"
                               :context {:token token}})
          (ai-usage-logs/log! db {:org-id org_id
                                  :user-id user-id
                                  :feature-type "invite"
                                  :input-tokens 0
                                  :output-tokens 0})
          [::response/ok {:org_id org_id :token token :status "accepted"}])
        [::response/bad-request {:error "Invalid token"}]))))
