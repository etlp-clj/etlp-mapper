(ns etlp-mapper.handler.whoami
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.identity :as identity]))

(defmethod ig/init-key :etlp-mapper.handler/whoami
  [_ _]
  (fn [request]
    (let [user   (identity/user request)
          org-id (identity/org-id request)
          roles  (identity/roles request)]
      [::response/ok {:user   (not-empty user)
                      :org_id org-id
                      :roles roles}])))

