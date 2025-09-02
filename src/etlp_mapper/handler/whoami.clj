(ns etlp-mapper.handler.whoami
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.handler/whoami
  [_ _]
  (fn [request]
    (let [claims (get-in request [:identity :claims])
          org-id (get-in request [:identity :org/id])]
      [::response/ok {:org_id org-id
                      :sub (:sub claims)
                      :email (:email claims)
                      :exp (:exp claims)
                      :roles (:roles claims)}])))

