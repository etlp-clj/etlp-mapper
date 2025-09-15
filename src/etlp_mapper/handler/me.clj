(ns etlp-mapper.handler.me
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]))

;; POST /me/active-org – set the currently active organisation for the user.
;; The real implementation would persist this choice and perhaps issue a new
;; token.  Here we simply echo back the requested organisation identifier.
(defmethod ig/init-key :etlp-mapper.handler.me/set-active-org
  [_ {:keys [db]}]
  (fn [{{:keys [org_id]} :body-params :as request}]
    (let [user-id (get-in request [:identity :claims :sub])]
      (audit-logs/log! db {:org-id org_id
                           :user-id user-id
                           :action "set-active-org"})
      (ai-usage-logs/log! db {:org-id org_id
                              :user-id user-id
                              :feature-type "active-org"
                              :input-tokens 0
                              :output-tokens 0})
      [::response/ok {:org_id org_id}])))

