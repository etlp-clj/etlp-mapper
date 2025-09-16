(ns etlp-mapper.handler.billing
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [etlp-mapper.identity :as identity]))

(defn- admin-role? [roles]
  (some #{:owner :admin} roles))

;; POST /billing/portal – return a stubbed billing portal URL.
(defmethod ig/init-key :etlp-mapper.handler.billing/portal
  [_ _]
  (fn [request]
    (let [roles  (identity/roles request)
          org-id (identity/org-id request)]
      (cond
        (nil? org-id)
        [::response/forbidden {:error "Organization context required"}]
        (admin-role? roles)
        [::response/ok {:url "https://billing.example.com/portal" :org_id org-id}]
        :else
        [::response/forbidden {:error "Insufficient role"}]))))
