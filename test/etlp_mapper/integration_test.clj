(ns etlp-mapper.integration-test
  (:require [ataraxy.handler :as ataraxy-handler]
            [ataraxy.response :as response]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.auth :as auth]
            [etlp-mapper.handler.invites]
            [etlp-mapper.handler.me]
            [etlp-mapper.handler.orgs]
            [integrant.core :as ig]
            [ragtime.jdbc :as rjdbc]
            [ragtime.repl :as rag])
  (:import (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (java.security KeyPairGenerator)
           (java.util UUID)
           (org.postgresql.util PSQLException)))

(def db-uri (or (System/getenv "JDBC_URL")
                "jdbc:postgresql://localhost:5432/mapper?user=postgres&password=postgres"))

(def issuer "test-issuer")
(def audience "test-audience")

(def ^:dynamic *db* nil)

(defn db-spec []
  (:spec *db*))

(defn uuid-str []
  (str (UUID/randomUUID)))

(defn uuid [s]
  (cond
    (instance? UUID s) s
    (string? s) (UUID/fromString s)
    (nil? s) nil
    :else (UUID/fromString (str s))))

(defn load-config []
  (let [resource (io/resource "etlp_mapper/config.edn")]
    (edn/read-string {:readers {'ig/ref identity
                                'duct/include identity}}
                     (slurp resource))))

(defn migrations [config]
  (let [base (:duct.profile/base config)
        ids  (get-in base [:duct.migrator/ragtime :migrations])]
    (map (fn [id]
           (let [{:keys [up down]} (get base [:duct.migrator.ragtime/sql id])]
             (rjdbc/sql-migration {:id (name id) :up up :down down})))
         ids)))

(defn connection-uri-for-schema [uri schema]
  (str uri
       (if (str/includes? uri "?") "&" "?")
       "currentSchema=" schema))

(defn migrate! [spec]
  (let [config   (load-config)
        rag-conf {:datastore (rjdbc/sql-database spec)
                  :migrations (migrations config)}]
    (rag/migrate rag-conf)))

(defn with-temp-db [f]
  (let [schema     (str "test_" (UUID/randomUUID))
        base-spec  {:connection-uri db-uri}
        schema-spec {:connection-uri (connection-uri-for-schema db-uri schema)}]
    (try
      (jdbc/execute! base-spec ["CREATE EXTENSION IF NOT EXISTS \"pgcrypto\""])
      (catch PSQLException e
        (when-not (= "23505" (.getSQLState e))
          (throw e))))
    (jdbc/execute! base-spec [(format "CREATE SCHEMA \"%s\"" schema)])
    (try
      (migrate! schema-spec)
      (binding [*db* {:spec schema-spec}]
        (f))
      (finally
        (try
          (jdbc/execute! base-spec [(format "DROP SCHEMA \"%s\" CASCADE" schema)])
          (catch Exception e
            (println "Failed to drop schema" schema (.getMessage e))))))))

(use-fixtures :each with-temp-db)

(defn gen-token [claims]
  (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
        kp  (.generateKeyPair kpg)
        pub (.getPublic kp)
        priv (.getPrivate kp)
        alg (Algorithm/RSA256 pub priv)
        builder (-> (JWT/create)
                    (.withIssuer issuer)
                    (.withAudience (into-array String [audience])))
        builder (reduce (fn [b [k v]]
                          (case k
                            :roles (.withArrayClaim b (name k) (into-array String (map name v)))
                            (.withClaim b (name k) (str v))))
                        builder
                        claims)
        token (.sign builder alg)
        verifier (fn [t]
                   (-> (JWT/require alg)
                       (.withIssuer issuer)
                       (.withAudience (into-array String [audience]))
                       .build
                       (.verify t)))]
    {:token token :verifier verifier}))

(defn wrap-handler [handler verifier {:keys [require-org? role]}]
  (let [handler (fn [req]
                  (let [resp (handler req)]
                    (if (vector? resp)
                      (ataraxy-handler/sync-default {:ataraxy/result resp})
                      resp)))
        handler (if require-org? ((auth/wrap-require-org) handler) handler)
        handler (if role ((auth/require-role role) handler) handler)
        handler ((auth/wrap-auth {:issuer issuer
                                  :audience audience
                                  :verifier verifier
                                  :db *db*}) handler)]
    handler))

(defn insert-org! [org-id name]
  (jdbc/insert! (db-spec)
                :organizations
                {:id   (uuid org-id)
                 :name name}))

(defn insert-user! [{:keys [id idp-sub email name last-used-org-id]}]
  (jdbc/insert! (db-spec)
                :users
                (cond-> {:id      (uuid id)
                         :idp_sub (or idp-sub id)
                         :email  email
                         :name   name}
                  last-used-org-id (assoc :last_used_org_id (uuid last-used-org-id)))))

(defn insert-member! [org-id user-id role]
  (jdbc/insert! (db-spec)
                :organization_members
                {:organization_id (uuid org-id)
                 :user_id         (uuid user-id)
                 :role            role}))

(extend-protocol audit-logs/AuditLogs
  clojure.lang.IPersistentMap
  (find-log [{:keys [spec]} id]
    (first (jdbc/query spec ["select * from audit_logs where id = ?" id])))
  (find-logs [{:keys [spec]} org-id]
    (jdbc/query spec ["select * from audit_logs where organization_id = ? order by created_at desc" org-id]))
  (create-log [{:keys [spec]} data]
    (let [row (-> data
                  (update :organization_id uuid)
                  (update :user_id uuid))]
      (first (jdbc/insert! spec :audit_logs row)))))

(defn headers [token & {:keys [org-id]}]
  (cond-> {"authorization" (str "Bearer " token)}
    org-id (assoc "x-org-id" (uuid org-id))))

(deftest onboarding-flow
  (let [user-id (uuid-str)
        {:keys [token verifier]} (gen-token {:sub user-id
                                             :email "owner@example.com"
                                             :name  "Owner"})
        create-handler    (ig/init-key :etlp-mapper.handler.orgs/create {:db *db*})
        set-active-handler (ig/init-key :etlp-mapper.handler.me/set-active-org {})
        create-app        (wrap-handler create-handler verifier {})
        set-active-app    (wrap-handler set-active-handler verifier {})]
    (with-redefs [audit-logs/log! (fn [& _] nil)]
      (let [resp     (create-app {:headers (headers token)})
            org-id   (get-in resp [:body :org_id])
            user-row (first (jdbc/query (db-spec)
                                        ["select email from users where idp_sub=?" user-id]))]
        (is (= 200 (:status resp)))
        (is (some? org-id))
        (is (= "owner@example.com" (:email user-row)))
        (insert-org! org-id "Integration Org")
        (let [active-resp (set-active-app {:headers     (headers token :org-id org-id)
                                           :body-params {:org_id org-id}})
              row         (first (jdbc/query (db-spec)
                                             ["select last_used_org_id from users where idp_sub=?" user-id]))]
          (is (= 200 (:status active-resp)))
          (is (= org-id (get-in active-resp [:body :org_id])))
          (is (= (uuid org-id) (:last_used_org_id row))))))))

(deftest invite-flow-and-rbac
  (let [org-id    (uuid-str)
        admin-id  (uuid-str)
        member-id (uuid-str)]
    (insert-org! org-id "Owner Org")
    (insert-user! {:id admin-id :email "admin@example.com" :name "Admin"})
    (insert-user! {:id member-id :email "member@example.com" :name "Member"})
    (insert-member! org-id admin-id "owner")
    (insert-member! org-id member-id "user")
    (let [{admin-token :token admin-verifier :verifier}
          (gen-token {:sub admin-id :email "admin@example.com" :name "Admin"})
          {member-token :token member-verifier :verifier}
          (gen-token {:sub member-id :email "member@example.com" :name "Member"})
          invite-create (ig/init-key :etlp-mapper.handler.invites/create {:db *db*})
          invite-accept (ig/init-key :etlp-mapper.handler.invites/accept {:db *db*})
          admin-app     (wrap-handler invite-create admin-verifier {:require-org? true :role :owner})
          accept-app    (wrap-handler invite-accept admin-verifier {})
          member-app    (wrap-handler invite-create member-verifier {:require-org? true :role :owner})
          resp          (admin-app {:headers (headers admin-token :org-id org-id)
                                    :ataraxy/result [nil org-id]})
          invite-token  (get-in resp [:body :token])
          accept-resp   (accept-app {:headers (headers admin-token)
                                     :body-params {:token invite-token
                                                   :org_id org-id}})
          forbidden     (member-app {:headers        (headers member-token :org-id org-id)
                                     :ataraxy/result [nil org-id]})]
      (is (= 200 (:status resp)))
      (is (string? invite-token))
      (is (= 200 (:status accept-resp)))
      (is (= "accepted" (get-in accept-resp [:body :status])))
      (is (= 403 (:status forbidden))))))

(deftest cross-org-isolation
  (let [org-a   (uuid-str)
        org-b   (uuid-str)
        user-id (uuid-str)
        fetch   (fn [{:keys [params identity]}]
                  (let [id     (:id params)
                        org-id (get-in identity [:org/id])
                        row    (first (jdbc/query (db-spec)
                                                  ["select id, organization_id from mappings where id = ? and organization_id = ?::uuid"
                                                   id org-id]))]
                    (if row
                      [::response/ok row]
                      [::response/not-found {:error "not-found"}])))]
    (insert-org! org-a "Org A")
    (insert-org! org-b "Org B")
    (let [{token :token verifier :verifier}
          (gen-token {:sub user-id :email "user@example.com" :name "User"})
          mapping    (first (jdbc/insert! (db-spec)
                                          :mappings
                                          {:title           "Example"
                                           :content         nil
                                           :organization_id (uuid org-a)}))
          mapping-id (:id mapping)
          app        (wrap-handler fetch verifier {:require-org? true})
          resp-a     (app {:headers (headers token :org-id org-a)
                           :params  {:id mapping-id}})
          resp-b     (app {:headers (headers token :org-id org-b)
                           :params  {:id mapping-id}})]
      (is (= 200 (:status resp-a)))
      (is (= (uuid org-a) (get-in resp-a [:body :organization_id])))
      (is (= 404 (:status resp-b))))))
