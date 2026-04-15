(ns etlp-mapper.handler.jute-dsl-spec
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]))

(defmethod ig/init-key :etlp-mapper.handler/jute-dsl-spec [_ _]
  (let [spec (-> (io/resource "etlp_mapper/jute_dsl_spec.json") slurp)]
    (fn [_]
      {:status 200
       :headers {"Content-Type" "application/json"
                 "Access-Control-Allow-Origin" "*"}
       :body spec})))
