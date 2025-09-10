(ns etlp-mapper.invite
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :etlp-mapper.invite/token [_ config]
  config)

