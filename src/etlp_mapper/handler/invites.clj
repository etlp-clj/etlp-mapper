(ns etlp-mapper.handler.invites
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.identity :as identity]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.organization-invites :as org-invites]
            [etlp-mapper.organization-members :as org-members])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)
           (java.sql Timestamp))

(defn- admin-role? [roles]
  (some #{:owner :admin} roles))
;; POST /orgs/:org-id/invites – create an invite token.  Requires the caller to
;; have an admin role within the organisation.
(defn- invite-expiry
  [ttl-minutes]
  (-> (Instant/now)
      (.plus (long ttl-minutes) ChronoUnit/MINUTES)
      (Timestamp/from)))

(defn- ensure-secret!
  [token-config]
  (if-let [secret (:app-secret token-config)]
    secret
    (throw (ex-info "Invite token secret missing" {}))))

(defmethod ig/init-key :etlp-mapper.handler.invites/create
  [_ {:keys [db token]}]
  (fn [{[_ path-org] :ataraxy/result :as request}]
    (let [org-id   (identity/org-id request)
          roles    (identity/roles request)
          user-id  (identity/user-id request)
          {:keys [email role]} (:body-params request)
          secret   (ensure-secret! token)
          ttl      (or (:ttl-minutes token) 60)
          invite-role (or role "mapper")]
      (cond
        (nil? org-id)
        [::response/forbidden {:error "Organization context required"}]

        (not= path-org org-id)
        [::response/forbidden {:error "Organization mismatch"}]

        (nil? user-id)
        [::response/forbidden {:error "User context required"}]

        (not (admin-role? roles))
        [::response/forbidden {:error "Insufficient role"}]

        (not (seq email))
        [::response/bad-request {:error "Invite email required"}]

        :else
        (let [token-value (org-invites/sign-token secret {:org-id org-id
                                                          :email email
                                                          :role invite-role})
              invite-id   (str (UUID/randomUUID))
              expires     (invite-expiry ttl)
              data {:id invite-id
                    :organization_id org-id
                    :email email
                    :role invite-role
                    :token token-value
                    :status "pending"
                    :expires_at expires}]
          (org-invites/upsert-invite db data)
          (audit-logs/log! db {:org-id org-id
                               :user-id user-id
                               :action "create-invite"
                               :context {:token token-value :email email}})
          (ai-usage-logs/log! db {:org-id org-id
                                  :user-id user-id
                                  :feature-type "invite"
                                  :input-tokens 0
                                  :output-tokens 0})
          [::response/ok {:org_id org-id :token token-value}])))))

;; POST /invites/accept – verify an invite token and add the user to the
;; organisation membership list.
(defmethod ig/init-key :etlp-mapper.handler.invites/accept
  [_ {:keys [db token]}]
  (fn [{{:keys [token org_id]} :body-params :as request}]
    (let [secret    (ensure-secret! token)
          claims    (when token (org-invites/verify-token secret token))
          claim-org (or (:org-id claims) (:org_id claims))
          claim-role (or (:role claims) "mapper")
          org-id    (or (identity/org-id request) org_id claim-org)
          user-id   (identity/user-id request)
          invite    (when org-id (org-invites/find-invite db org-id token))]
      (cond
        (nil? token)
        [::response/bad-request {:error "Invalid token"}]

        (nil? claims)
        [::response/bad-request {:error "Invalid token"}]

        (nil? org-id)
        [::response/forbidden {:error "Organization context required"}]

        (and claim-org (not= claim-org org-id))
        [::response/forbidden {:error "Organization mismatch"}]

        (and org_id (not= org_id org-id))
        [::response/forbidden {:error "Organization mismatch"}]

        (nil? user-id)
        [::response/forbidden {:error "User context required"}]

        (nil? invite)
        [::response/bad-request {:error "Invite not found"}]

        :else
        (do
          (when-not (org-members/member? db org-id user-id)
            (org-members/add-member db {:organization_id org-id
                                        :user_id user-id
                                        :role claim-role}))
          (org-invites/consume-invite db org-id token)
          (audit-logs/log! db {:org-id org-id
                               :user-id user-id
                               :action "accept-invite"
                               :context {:token token}})
          (ai-usage-logs/log! db {:org-id org-id
                                  :user-id user-id
                                  :feature-type "invite"
                                  :input-tokens 0
                                  :output-tokens 0})
          [::response/ok {:org_id org-id :token token :status "accepted"}])))))
