(ns etlp-mapper.handler.mappings
  (:require [ataraxy.response :as response]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [keywordize-keys]]
            duct.database.sql
            [integrant.core :as ig]
            [jute.core :as jt]
            [clojure.string :as s]
            [clj-http.client :as http]
            [yaml.core :as yaml]
            [duct.handler.sql :as sql]
            [cheshire.core :as json]
            [etlp-mapper.service.audit-logs :as audit-logs]
            [etlp-mapper.service.ai-usage-logs :as ai-usage-logs])
  (:import java.util.Base64))

(defprotocol Mappings
  (apply-mapping [db org-id id data]))

(def parse-decoded-yml (fn [template]
                         (yaml/parse-string template :keywords true)))

(extend-protocol Mappings
  duct.database.sql.Boundary
  (apply-mapping [{db :spec} org-id id data]
    (let [results (jdbc/query db ["select * from mappings where id = ? and org_id = ?" id org-id])
          template (-> results
                       first
                       :content
                       keywordize-keys
                       :yaml
                       parse-decoded-yml)
          compiled (jt/compile template)]
      (compiled data))))

(defn create [request]
  (let [org-id      (get-in request [:identity :org/id])
        yaml-content (slurp (:body request))
        parsed-data  (yaml/parse-string yaml-content :keywords true)
        template     (-> parsed-data :template)
        scope        (-> parsed-data :scope)
        compiled     (jt/compile template)]
    (assoc {:request (compiled scope)} :org/id org-id)))


(defn extract-jute-template [response]
  (let [text (:text (first (:choices response)))]
    (s/trim text)))



(defmethod ig/init-key :etlp-mapper.handler/apply-mappings [_ {:keys [db]}]
  (fn [{[_ id data] :ataraxy/result :as request}]
    (let [org-id (get-in request [:identity :org/id])
          user-id (get-in request [:identity :claims :sub])]
      (try
        (let [translated (apply-mapping db org-id id data)]
          (audit-logs/log! db {:org-id org-id
                               :user-id user-id
                               :action "apply-mapping"
                               :context {:mapping-id id}})
          (ai-usage-logs/log! db {:org-id org-id
                                  :user-id user-id
                                  :feature-type "transform"})
          [::response/ok {:result translated :org/id org-id}])
        (catch Exception e
          (println e)
          [::response/bad-request {:error (str e)}])))))


(defmethod ig/init-key :etlp-mapper.handler/mappings [_ {:keys [db]}]
  (fn [request]
    (let [org-id (get-in request [:identity :org/id])
          user-id (get-in request [:identity :claims :sub])]
      (try
        (let [translated (create request)]
          (pprint translated)
          (audit-logs/log! db {:org-id org-id
                               :user-id user-id
                               :action "create-mapping"
                               :context {:request (:request translated)}})
          [::response/ok (assoc translated :org/id org-id)])
        (catch Exception e
          (println e)
          [::response/bad-request {:error (.getMessage e)}])))))
