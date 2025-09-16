(ns etlp-mapper.handler.me
  (:require [ataraxy.response :as response]
            [clojure.java.jdbc :as jdbc]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.identity :as identity]))

;; POST /me/active-org – set the currently active organisation for the user.
;; Validates that the authenticated user is a member of the organisation and
;; persists the choice by updating `users.last_used_org_id`.
(defn- db-spec
  "Extract a JDBC-compatible spec or connection from the Integrant SQL boundary."
  [db]
  (or (:spec db) db))

(defn- member?
  [spec org-id user-id]
  (seq (jdbc/query spec
                   ["select 1 from organization_members where organization_id = ? and user_id = ? limit 1"
                    org-id user-id])))

(defmethod ig/init-key :etlp-mapper.handler.me/set-active-org
  [_ {:keys [db]}]
  (fn [{{:keys [org_id]} :body-params :as request}]
    (let [active-org (or org_id (identity/org-id request))
          user-id    (identity/user-id request)
          spec       (db-spec db)]
      (cond
        (nil? user-id)
        [::response/forbidden {:error "User context required"}]

        (nil? active-org)
        [::response/bad-request {:error "Organization id required"}]

        (not (member? spec active-org user-id))
        [::response/forbidden {:error "Membership required"}]

        :else
        (do
          (jdbc/update! spec :users {:last_used_org_id active-org}
                        ["id = ?" user-id])
          (audit-logs/log! db {:org-id active-org
                               :user-id user-id
                               :action "set-active-org"})
          (ai-usage-logs/log! db {:org-id active-org
                                  :user-id user-id
                                  :feature-type "active-org"
                                  :input-tokens 0
                                  :output-tokens 0})
          [::response/ok {:org_id active-org}])))))

