(ns etlp-mapper.handler.mappings
  (:require [ataraxy.response :as response]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [keywordize-keys]]
            duct.database.sql
            [integrant.core :as ig]
            [jute.core :as jt]
            [yaml.core :as yaml]
            [duct.handler.sql :as sql]
            [cheshire.core :as json])
  (:import java.util.Base64))

(defprotocol Mappings
  (apply-mapping [db id data]))

(def parse-decoded-yml (fn [template]
                         (yaml/parse-string template :keywords true)))

(extend-protocol Mappings
  duct.database.sql.Boundary
  (apply-mapping [{db :spec} id data]
    (let [results (jdbc/query db [(str "select * from mappings "
                                       (format "where id='%s'" id))])
          template (-> results
                       first
                       :content
                       keywordize-keys
                       :yaml
                       parse-decoded-yml)
          compiled (jt/compile template)]
      (compiled data))))

(defn create [request]
  (let [yaml-content (slurp (:body request))
        parsed-data (yaml/parse-string yaml-content :keywords true)
        template (-> parsed-data :template)
        scope (-> parsed-data :scope)
        compiled (jt/compile template)]
    {:request (compiled scope)}))

(defmethod ig/init-key :etlp-mapper.handler/apply-mappings [_ {:keys [db]}]
  (fn [{[_ id data] :ataraxy/result}]
    (try
      (let [translated (apply-mapping db id data)]
        [::response/ok {:result translated}])
      (catch Exception e
        (println e)
        [::response/bad-request {:error (str e)}]))))


(defmethod ig/init-key :etlp-mapper.handler/mappings [_ {:keys [db]}]
  (fn [opts]
    (try
      (let [translated (create opts)] 
        (pprint translated)
        [::response/ok translated])
      (catch Exception e
        (println e)
        [::response/bad-request {:error (.getMessage e)}]))))
