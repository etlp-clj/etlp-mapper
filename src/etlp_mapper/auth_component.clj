(ns etlp-mapper.auth-component
  "Integrant components wiring authentication middleware."
  (:require [integrant.core :as ig]
            [etlp-mapper.auth :as auth]))

(defmethod ig/init-key :etlp/middleware.auth
  [_ opts]
  (auth/wrap-auth opts))

(defmethod ig/init-key :etlp/middleware.require-org
  [_ _]
  (auth/wrap-require-org))

(defmethod ig/init-key :etlp/middleware.require-role
  [_ {:keys [role]}]
  (auth/require-role role))

