(ns etlp-mapper.keycloak
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.keycloak/admin
  [_ config]
  config)

(defmethod ig/halt-key! :etlp-mapper.keycloak/admin
  [_ _]
  nil)
