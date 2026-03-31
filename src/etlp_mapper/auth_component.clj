(ns etlp-mapper.auth-component
  "Integrant components wiring authentication middleware."
  (:require [integrant.core :as ig]
            [etlp-mapper.auth :as auth]))

(defn- oidc-enabled? []
  (not= "false" (System/getenv "OIDC_ENABLED")))

(defn- dev-passthrough []
  (fn [handler]
    (fn [req]
      (handler (assoc req :identity
                      {:method :dev
                       :org/id (or (System/getenv "DEV_ORG_ID") "lithrim-dev")
                       :claims {:sub "dev-user"
                                :email "dev@lithrim.com"
                                :roles ["owner"]}})))))

(defmethod ig/init-key :etlp-mapper.auth-component/auth
  [_ opts]
  (if (oidc-enabled?)
    (auth/wrap-auth opts)
    (dev-passthrough)))

(defmethod ig/init-key :etlp-mapper.auth-component/require-org
  [_ _]
  (if (oidc-enabled?)
    (auth/wrap-require-org)
    (dev-passthrough)))

(defmethod ig/init-key :etlp-mapper.auth-component/require-role
  [_ {:keys [role]}]
  (if (oidc-enabled?)
    (auth/require-role role)
    (dev-passthrough)))

