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
    (let [roles (get-in request [:identity :claims :roles])]
      (if (admin-role? roles)
        [::response/ok {:url "https://billing.example.com/portal"}]
        [::response/forbidden {:error "Insufficient role"}]))))

