(ns etlp-mapper.handler.invites
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.auth :as auth]
            [etlp-mapper.organization-invites :as org-invites]
            [etlp-mapper.organization-members :as org-members]))

;; POST /orgs/:org-id/invites – create an invite token.  Requires the caller to
;; have an admin role within the organisation.
(defmethod ig/init-key :etlp-mapper.handler.invites/create
  [_ {:keys [db token]}]
  (let [{:keys [app-secret]} token
        handler (fn [{[_ org-id] :ataraxy/result
                      {:keys [email]} :body-params
                      :as request}]
                  (if email
                    (let [token-str (org-invites/sign-token app-secret
                                                           {:org-id org-id
                                                            :email email})
                          invite-id (str (java.util.UUID/randomUUID))
                          user-id (get-in request [:identity :user :id])]
                      (org-invites/upsert-invite db {:id invite-id
                                                     :organization_id org-id
                                                     :email email
                                                     :token token-str})
                      (audit-logs/log! db {:org-id org-id
                                           :user-id user-id
                                           :action "create-invite"
                                           :context {:token token-str
                                                     :email email}})
                      [::response/ok {:org_id org-id :token token-str}])
                    [::response/bad-request {:error "Email required"}]))]
    ((auth/require-role :admin) handler)))

;; POST /invites/accept – verify an invite token and add the user to the
;; organisation membership list.
(defmethod ig/init-key :etlp-mapper.handler.invites/accept
  [_ {:keys [db token]}]
  (let [{:keys [app-secret]} token]
    (fn [{{token :token} :body-params :as request}]
      (let [user-id (get-in request [:identity :user :id])
            claims  (when token (org-invites/verify-token app-secret token))
            invite  (when token (org-invites/find-invite db token))]
        (if (and claims invite)
          (do
            (org-members/add-member db {:organization_id (:organization_id invite)
                                        :user_id user-id
                                        :role "mapper"})
            (org-invites/consume-invite db token)
            (audit-logs/log! db {:org-id (:organization_id invite)
                                 :user-id user-id
                                 :action "accept-invite"
                                 :context {:token token}})
            [::response/ok {:org_id (:organization_id invite)
                            :token token
                            :status "accepted"}])
          [::response/bad-request {:error "Invalid token"}])))))
