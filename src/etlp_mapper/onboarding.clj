(ns etlp-mapper.onboarding
  "Functions for setting up a new organization and associated resources."
  (:require [clojure.java.jdbc :as jdbc]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [etlp-mapper.audit-logs :as audit-logs]))

(defn- admin-token
  [{:keys [url realm client-id client-secret]}]
  (-> (http/post (str url "/realms/" realm "/protocol/openid-connect/token")
                 {:form-params {:grant_type "client_credentials"
                                :client_id client-id
                                :client_secret client-secret}
                  :content-type :x-www-form-urlencoded
                  :as :json})
      :body
      :access_token))

(defn- provision-group!
  "Create a group in Keycloak for the organization."
  [{:keys [url realm] :as kc} org-id]
  (when (and url realm)
    (let [token (admin-token kc)]
      (http/post (str url "/admin/realms/" realm "/groups")
                 {:headers {"Authorization" (str "Bearer " token)}
                  :content-type :json
                  :body (json/encode {:name org-id})}))
    nil))

(defn- upsert-user!
  [tx {:keys [idp-sub email name]}]
  (first
   (jdbc/query tx
               [(str "insert into users as u (idp_sub,email,name) values (?,?,?) "
                     "on conflict (idp_sub) do update set email=excluded.email, name=excluded.name "
                     "returning u.id, u.email, u.idp_sub")
                idp-sub email name])))

(defn- insert-org!
  [tx {:keys [name created-user-id]}]
  (let [id (str (java.util.UUID/randomUUID))]
    (:id
     (first
      (jdbc/query tx
                  [(str "insert into organizations (id,name,created_user_id) values (?::uuid, ?, ?::uuid) "
                        "on conflict (name) do update set name=excluded.name, created_user_id=excluded.created_user_id returning id")
                   id name created-user-id])))))

(defn- ensure-membership!
  [tx org-id user-id]
  (jdbc/execute! tx
                 [(str "insert into organization_members (organization_id,user_id,role) "
                       "values (?::uuid, ?::uuid, ?) "
                       "on conflict (organization_id, user_id) do nothing")
                  org-id user-id "owner"]))

(defn- ensure-subscription!
  [tx org-id]
  (jdbc/execute! tx
                 [(str "insert into organization_subscriptions (organization_id,plan,status) "
                       "values (?::uuid, ?, ?) "
                       "on conflict (organization_id) do update set plan=excluded.plan, status=excluded.status")
                  org-id "free" "active"]))

(defn ensure-org!
  "Ensure an organization exists for the given user and name. Returns the organization id."
  [db kc {:keys [name user]}]
  (jdbc/with-db-transaction [tx db]
    (let [user-row (upsert-user! tx user)
          org-id   (insert-org! tx {:name name :created-user-id (:id user-row)})]
      (ensure-membership! tx org-id (:id user-row))
      (ensure-subscription! tx org-id)
      (jdbc/update! tx :users {:last_used_org_id org-id}
                    ["id = ?" (:id user-row)])
      (audit-logs/log! tx {:org-id org-id
                           :user-id (:id user-row)
                           :action "create-organization"})
      (provision-group! kc org-id)
      org-id)))
