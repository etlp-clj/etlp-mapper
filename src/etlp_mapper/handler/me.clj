(ns etlp-mapper.handler.me
  (:require [ataraxy.response :as response]
            [clojure.java.jdbc :as jdbc]
            [integrant.core :as ig]))

;; POST /me/active-org – set the currently active organisation for the user.
;; Validates that the authenticated user is a member of the organisation and
;; persists the choice by updating `users.last_used_org_id`.
(defmethod ig/init-key :etlp-mapper.handler.me/set-active-org
  [_ {:keys [db]}]
  (fn [{{:keys [org_id]} :body-params :as request}]
    (let [user-id (get-in request [:identity :user :id])
          member? (seq (jdbc/query (:spec db)
                                   ["select 1 from organization_members where organization_id = ? and user_id = ? limit 1"
                                    org_id user-id]))]
      (if member?
        (do
          (jdbc/update! (:spec db) :users {:last_used_org_id org_id} ["id = ?" user-id])
          [::response/ok {:org_id org_id}])
        [::response/forbidden {:error "Organization access denied"}]))))

