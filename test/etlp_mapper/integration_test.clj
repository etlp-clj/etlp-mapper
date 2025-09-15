(ns etlp-mapper.integration-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [ragtime.jdbc :as rjdbc]
            [ragtime.repl :as rag]
            [ring.util.http-response :as http]
            [etlp-mapper.auth :as auth]
            [etlp-mapper.handler.orgs]
            [etlp-mapper.handler.invites]
            [etlp-mapper.handler.me])
  (:import (java.security KeyPairGenerator)
           (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)))

(def db-uri (or (System/getenv "JDBC_URL")
                "jdbc:postgresql://localhost:5432/mapper?user=postgres&password=postgres"))

(def ^:dynamic *db* nil)

(defn load-config []
  (-> "etlp_mapper/config.edn"
      io/resource
      slurp
      (edn/read-string {:readers {'ig/ref identity
                                  'duct/include identity}})))

(defn migrations [config]
  (let [base (:duct.profile/base config)
        mig-ids (get-in base [:duct.migrator/ragtime :migrations])]
    (for [id mig-ids
          :let [{:keys [up down]} (get base [:duct.migrator.ragtime/sql id])]]
      (rjdbc/sql-migration {:id (name id) :up up :down down}))))

(defn with-temp-db [f]
  (let [schema (str "test_" (java.util.UUID/randomUUID))
        base-spec {:connection-uri db-uri}
        spec {:connection-uri (str db-uri "&currentSchema=" schema)}]
    (jdbc/execute! base-spec [(str "CREATE SCHEMA " schema)])
    (let [config (load-config)
          rag-conf {:datastore (rjdbc/sql-database spec)
                    :migrations (migrations config)}]
      (rag/migrate rag-conf)
      (try
        (binding [*db* {:spec spec}]
          (f))
        (finally
          (jdbc/execute! base-spec [(str "DROP SCHEMA " schema " CASCADE")]))))))

(use-fixtures :once with-temp-db)

(def issuer "test-issuer")
(def audience "test-audience")

(defn gen-token
  "Generate a signed JWT and verifier."
  [claims]
  (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
        kp  (.generateKeyPair kpg)
        pub (.getPublic kp)
        priv (.getPrivate kp)
        alg (Algorithm/RSA256 pub priv)
        builder (-> (JWT/create)
                    (.withIssuer issuer)
                    (.withAudience (into-array String [audience])))
        builder (reduce (fn [b [k v]] (.withClaim b (name k) (str v))) builder claims)
        token (.sign builder alg)
        verifier (fn [t]
                   (-> (JWT/require alg)
                       (.withIssuer issuer)
                       (.withAudience (into-array String [audience]))
                       .build
                       (.verify t)))]
    {:token token :verifier verifier}))

(defn wrap
  [handler verifier & {:keys [require-org? role]}]
  (-> handler
      (auth/wrap-auth {:issuer issuer :audience audience :verifier verifier :db *db*})
      (cond-> require-org? (auth/wrap-require-org))
      (cond-> role ((auth/require-role role)))))

(deftest onboarding-flow
  (let [{:keys [token verifier]} (gen-token {:sub "user-1" :email "u@example" :name "User"})
        create (ig/init-key :etlp-mapper.handler.orgs/create {:db *db*})
        app (wrap create verifier)
        resp (app {:headers {"authorization" (str "Bearer " token)}})
        org-id (get-in resp [:body :org_id])
        set-active (ig/init-key :etlp-mapper.handler.me/set-active-org {})
        active-app (wrap set-active verifier)
        resp2 (active-app {:headers {"authorization" (str "Bearer " token)}
                           :body-params {:org_id org-id}})]
    (is (= 200 (:status resp)))
    (is (some? org-id))
    (is (= 200 (:status resp2)))
    (is (= org-id (get-in resp2 [:body :org_id])))))

(deftest invite-flow-and-rbac
  (let [{admin-token :token admin-verifier :verifier} (gen-token {:sub "admin" :email "a@example" :name "Admin" :roles ["owner"]})
        create-org (ig/init-key :etlp-mapper.handler.orgs/create {:db *db*})
        org-app (wrap create-org admin-verifier)
        org-id (get-in (org-app {:headers {"authorization" (str "Bearer " admin-token)}}) [:body :org_id])
        invite-h (ig/init-key :etlp-mapper.handler.invites/create {:db *db*})
        invite-app (wrap invite-h admin-verifier :require-org? true :role :owner)
        resp (invite-app {:headers {"authorization" (str "Bearer " admin-token)
                                    "x-org-id" org-id}
                          :ataraxy/result [nil org-id]})
        invite-token (get-in resp [:body :token])
        accept-h (ig/init-key :etlp-mapper.handler.invites/accept {:db *db*})
        accept-app (wrap accept-h admin-verifier)
        resp-accept (accept-app {:headers {"authorization" (str "Bearer " admin-token)}
                                 :body-params {:token invite-token :org_id org-id}})
        {user-token :token user-verifier :verifier} (gen-token {:sub "user" :email "u@example" :name "User" :roles ["user"]})
        invite-app-user (wrap invite-h user-verifier :require-org? true :role :owner)
        resp-forbidden (invite-app-user {:headers {"authorization" (str "Bearer " user-token)
                                                   "x-org-id" org-id}
                                         :ataraxy/result [nil org-id]})]
    (is (= 200 (:status resp)))
    (is (= 200 (:status resp-accept)))
    (is (= "accepted" (get-in resp-accept [:body :status])))
    (is (= 403 (:status resp-forbidden)))))

(deftest cross-org-isolation
  (let [{:keys [token verifier]} (gen-token {:sub "user-1" :email "u@example" :name "User"})
        create-org (ig/init-key :etlp-mapper.handler.orgs/create {:db *db*})
        org-app (wrap create-org verifier)
        org1 (get-in (org-app {:headers {"authorization" (str "Bearer " token)}}) [:body :org_id])
        org2 (get-in (org-app {:headers {"authorization" (str "Bearer " token)}}) [:body :org_id])
        row (first (jdbc/insert! (:spec *db*) :mappings {:title "t" :content nil :organization_id org1}))
        map-id (:id row)
        fetch (fn [req]
                (let [id (-> req :params :id)
                      org-id (get-in req [:identity :org/id])
                      r (first (jdbc/query (:spec *db*)
                                           ["select id, organization_id from mappings where id=? and organization_id=?::uuid" id org-id]))]
                  (if r
                    (http/ok r)
                    (http/not-found {:error "not-found"}))))
        app (wrap fetch verifier :require-org? true)
        resp1 (app {:headers {"authorization" (str "Bearer " token)
                              "x-org-id" org1}
                    :params {:id map-id}})
        resp2 (app {:headers {"authorization" (str "Bearer " token)
                              "x-org-id" org2}
                    :params {:id map-id}})]
    (is (= 200 (:status resp1)))
    (is (= 404 (:status resp2)))))

