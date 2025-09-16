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
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]
            [etlp-mapper.identity :as identity])
  (:import java.util.Base64))

(defprotocol Mappings
  (apply-mapping [db org-id id data]))

(def parse-decoded-yml (fn [template]
                         (yaml/parse-string template :keywords true)))

(extend-protocol Mappings
  duct.database.sql.Boundary
  (apply-mapping [{db :spec} org-id id data]
    (let [results (jdbc/query db ["select * from mappings where id = ? and organization_id = ?::uuid" id org-id])
          template (-> results
                       first
                       :content
                       keywordize-keys
                       :yaml
                       parse-decoded-yml)
          compiled (jt/compile template)]
      (compiled data))))

(defn- log-mapping-event!
  [db org-id user-id action feature-type context]
  (when org-id
    (audit-logs/log! db {:org-id org-id
                         :user-id user-id
                         :action action
                         :context context})
    (ai-usage-logs/log! db {:org-id org-id
                            :user-id user-id
                            :feature-type feature-type
                            :input-tokens 0
                            :output-tokens 0})))

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
          user-id (identity/user-id request)]
      (try
        (let [translated (apply-mapping db org-id id data)]
          (log-mapping-event! db org-id user-id "apply-mapping" "transform" {:mapping-id id})
          [::response/ok {:result translated :org/id org-id}])
        (catch Exception e
          (println e)
          [::response/bad-request {:error (str e)}])))))

(defmethod ig/init-key :etlp-mapper.handler.mappings/create
  [_ {:keys [db handler]}]
  (assert handler "Missing SQL handler for mapping creation")
  (fn [{[_ title _] :ataraxy/result :as request}]
    (let [org-id (get-in request [:identity :org/id])
          user-id (identity/user-id request)
          inferred-title (or title (get-in request [:body-params :title]))
          context (cond-> {}
                    inferred-title (assoc :title inferred-title))]
      (log-mapping-event! db org-id user-id "create-mapping" "mapping-create" context)
      (handler request))))

(defmethod ig/init-key :etlp-mapper.handler.mappings/update
  [_ {:keys [db handler]}]
  (assert handler "Missing SQL handler for mapping update")
  (fn [{[_ mapping-id content] :ataraxy/result :as request}]
    (let [org-id (get-in request [:identity :org/id])
          user-id (identity/user-id request)
          context (-> {:mapping-id mapping-id}
                      (cond-> content (assoc :has-content true)))]
      (log-mapping-event! db org-id user-id "update-mapping" "mapping-update" context)
      (handler request))))

(defmethod ig/init-key :etlp-mapper.handler.mappings/destroy
  [_ {:keys [db handler]}]
  (assert handler "Missing SQL handler for mapping delete")
  (fn [{[_ mapping-id] :ataraxy/result :as request}]
    (let [org-id (get-in request [:identity :org/id])
          user-id (identity/user-id request)]
      (log-mapping-event! db org-id user-id "destroy-mapping" "mapping-delete" {:mapping-id mapping-id})
      (handler request))))

(defmethod ig/init-key :etlp-mapper.handler.mappings [_ {:keys [db]}]
  (fn [request]
    (let [org-id (get-in request [:identity :org/id])
          user-id (identity/user-id request)]
      (try
        (let [translated (create request)]
          (pprint translated)
          (log-mapping-event! db org-id user-id "create-mapping" "mapping-create" {:request (:request translated)})
          [::response/ok (assoc translated :org/id org-id)])
        (catch Exception e
          (println e)
          [::response/bad-request {:error (.getMessage e)}])))))
