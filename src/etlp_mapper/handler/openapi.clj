(ns etlp-mapper.handler.openapi
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

(defmethod ig/init-key :etlp-mapper.handler/openapi [_ _]
  (let [schema (-> (io/resource "etlp_mapper/openapi.json") slurp)]
    (fn [_]
      {:status 200
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body schema})))
