(ns etlp-mapper.handler.whoami
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.handler/whoami
  [_ _]
  (fn [request]
    (let [identity (:identity request)
          user (-> (:user identity)
                   (assoc :exp (get-in identity [:claims :exp])))
          org-id (:org/id identity)
          roles (:roles identity)]
      [::response/ok {:user   user
                      :org_id org-id
                      :roles roles}])))

