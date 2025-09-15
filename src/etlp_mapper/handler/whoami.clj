(ns etlp-mapper.handler.whoami
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.handler/whoami
  [_ _]
  (fn [request]
    (let [identity (:identity request)
          org-id   (:org/id identity)
          user     (some-> identity :user
                           (select-keys [:id :email :idp-sub :last-used-org-id]))]
      [::response/ok {:user   user
                      :org_id org-id
                      :roles (:roles identity)}])))

