(ns etlp-mapper.auth-component
  "Integrant components wiring authentication middleware."
  (:require [integrant.core :as ig]
            [etlp-mapper.auth :as auth]))

(defmethod ig/init-key :etlp-mapper.auth-component/auth
  [_ opts]
  (auth/wrap-auth opts))

(defmethod ig/init-key :etlp-mapper.auth-component/require-org
  [_ _]
  (auth/wrap-require-org))

(defmethod ig/init-key :etlp-mapper.auth-component/require-role
  [_ {:keys [role]}]
  (auth/require-role role))

