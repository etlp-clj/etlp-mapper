(ns etlp-mapper.handler.invites
  (:require [clojure.string :as str]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.identity :as identity]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.organization-invites :as org-invites]
            [etlp-mapper.organization-members :as org-members]
            [etlp-mapper.config :as config])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)
           (java.util UUID)
           (java.sql Timestamp)))

(defn- forbid
  "Construct a forbidden response tuple with the provided message."
  [msg]
  [:ataraxy.response/forbidden {:error msg}])

(defn- require-org
  "Ensure the identity map exposes an organisation identifier."
  [identity]
  (if-let [org-id (identity/org-id {:identity identity})]
    (assoc identity :org/id org-id)
    (forbid "Organization context required")))

(defn- require-user
  "Ensure the identity map exposes a user identifier."
  [identity]
  (if-let [user-id (identity/user-id {:identity identity})]
    (assoc identity :user/id user-id)
    (forbid "User context required")))

(defn- require-admin
  "Ensure the identity includes the :admin role."
  [identity]
  (let [roles (identity/roles {:identity identity})]
    (if (contains? roles :admin)
      (assoc identity :roles roles)
      (forbid "Insufficient role"))))

(defn- ensure-org-match
  "Ensure the identity organisation matches the requested identifier."
  [identity org-id]
  (if (= (:org/id identity) org-id)
    identity
    (forbid "Organization mismatch")))

(defn ^:private require-secret
  [secret]
  (if (seq secret)
    secret
    [:ataraxy.response/forbidden {:error "Invite token secret missing"}]))

(defn- invite-expiry
  [ttl-minutes]
  (-> (Instant/now)
      (.plus (long ttl-minutes) ChronoUnit/MINUTES)
      (Timestamp/from)))

(defn- apply-guards
  "Thread the identity through guard functions, stopping on the first error."
  [identity & guards]
  (reduce (fn [state guard]
            (if (vector? state)
              (reduced state)
              (guard state)))
          identity
          guards))

;; POST /orgs/:org-id/invites – create an invite token.  Requires the caller to
;; have an admin role within the organisation.
(defmethod ig/init-key :etlp-mapper.handler.invites/create
  [_ {:keys [db token]}]
  (fn [{[_ path-org] :ataraxy/result :keys [identity body-params]}]
    (let [secret (require-secret (or (:app-secret token)
                                     (config/invite-secret)))]
      (if (vector? secret)
        secret
        (let [identity* (apply-guards identity
                                      require-org
                                      #(ensure-org-match % path-org)
                                      require-user
                                      require-admin)
              {:keys [email role]} body-params
              invite-role (or role "mapper")
              ttl (or (:ttl-minutes token) 60)
              email* (some-> email str/trim)]
          (cond
            (vector? identity*) identity*
            (not (seq email*))
            [:ataraxy.response/bad-request {:error "Invite email required"}]
            :else
            (let [org-id (:org/id identity*)
                  user-id (:user/id identity*)
                  token-value (org-invites/sign-token secret {:org-id org-id
                                                              :email email*
                                                              :role invite-role})
                  invite-id (str (UUID/randomUUID))
                  expires (invite-expiry ttl)
                  data {:id invite-id
                        :organization_id org-id
                        :email email*
                        :role invite-role
                        :token token-value
                        :status "pending"
                        :expires_at expires}]
              (org-invites/upsert-invite db data)
              (audit-logs/log! db {:org-id org-id
                                   :user-id user-id
                                   :action "create-invite"
                                   :context {:token token-value
                                             :email email*
                                             :status "pending"}})
              (ai-usage-logs/log! db {:org-id org-id
                                      :user-id user-id
                                      :feature-type "invite"
                                      :input-tokens 0
                                      :output-tokens 0})
              [:ataraxy.response/ok {:token token-value}])))))))

;; POST /invites/accept – verify an invite token and add the user to the
;; organisation membership list.
(defmethod ig/init-key :etlp-mapper.handler.invites/accept
  [_ {:keys [db token]}]
  (fn [{{:keys [token org_id]} :body-params :as request}]
    (let [secret (require-secret (or (:app-secret token)
                                     (config/invite-secret)))]
      (cond
        (nil? token)
        [:ataraxy.response/bad-request {:error "Invalid token"}]

        (vector? secret)
        secret

        :else
        (let [claims (org-invites/verify-token secret token)
              claim-org (or (:org-id claims) (:org_id claims))
              claim-role (or (:role claims) "mapper")
              org-id (or (identity/org-id request) org_id claim-org)
              user-id (identity/user-id request)
              invite (when org-id (org-invites/find-invite db org-id token))]
          (cond
            (nil? claims)
            [:ataraxy.response/bad-request {:error "Invalid token"}]

            (nil? org-id)
            (forbid "Organization context required")

            (and claim-org (not= claim-org org-id))
            (forbid "Organization mismatch")

            (and org_id (not= org_id org-id))
            (forbid "Organization mismatch")

            (nil? user-id)
            (forbid "User context required")

            (nil? invite)
            [:ataraxy.response/bad-request {:error "Invite not found"}]

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
              [:ataraxy.response/ok {:org_id org-id :token token :status "accepted"}])))))))
