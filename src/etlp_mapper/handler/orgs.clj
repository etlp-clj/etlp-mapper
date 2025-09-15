(ns etlp-mapper.handler.orgs
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.onboarding :as onboarding]))

(defmethod ig/init-key :etlp-mapper.handler.orgs/create
  [_ {:keys [db kc]}]
  (fn [{:keys [identity body-params]}]
    (if (get-in identity [:org/id])
      [::response/forbidden {:error "Organization already selected"}]
      (let [user (get identity :user)
            name (:name body-params)
            org-id (onboarding/ensure-org! db kc {:name name :user user})]
        [::response/ok {:org_id org-id}]))))
