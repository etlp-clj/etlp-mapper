(ns etlp-mapper.handler.billing
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]))

(defn- admin-role? [roles]
  (some #{:owner "owner" :admin "admin"} roles))

;; POST /billing/portal – return a stubbed billing portal URL for owners or
;; administrators of the active organisation.
(defmethod ig/init-key :etlp-mapper.handler.billing/portal
  [_ _]
  (fn [request]
    (let [roles  (get-in request [:identity :roles])
          org-id (get-in request [:identity :org/id])]
      (cond
        (nil? org-id)
        [::response/forbidden {:error "Organization context required"}]
        (admin-role? roles)
        [::response/ok {:url "https://billing.example.com/portal" :org_id org-id}]
        :else
        [::response/forbidden {:error "Insufficient role"}]))))

