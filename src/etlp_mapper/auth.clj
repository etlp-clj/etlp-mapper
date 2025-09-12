(ns etlp-mapper.auth
  "Ring middleware for validating Keycloak OIDC access tokens and
  enriching the request with database backed user and organization
  context."
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [ring.util.http-response :as http])
  (:import (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)
           (com.auth0.jwk JwkProviderBuilder)
           (com.auth0.jwt.exceptions JWTVerificationException)
           (java.net URL)
           (java.nio.charset StandardCharsets)
           (java.util Base64)
           (java.util.concurrent TimeUnit)))

(defn- bearer-token
  "Extract the bearer token from the Authorization header."
  [req]
  (some-> (get-in req [:headers "authorization"])
          (str/split #" ")
          second))

(defn- decode-claims
  "Decode the claims map from a verified JWT."
  [decoded]
  (let [payload (.getPayload decoded)
        bytes   (.decode (Base64/getUrlDecoder) payload)]
    (json/parse-string (String. bytes StandardCharsets/UTF_8) true)))

(defn- unauthorized
  [message]
  (-> (http/unauthorized {:error message})
      (http/header "WWW-Authenticate" "Bearer realm=\"mapify\"")))

(defn- forbidden
  [message]
  (http/forbidden {:error message}))

(defn- build-verifier
  "Create a function that verifies tokens using a JWKS URI.

  Throws an explanatory exception when the `jwks-uri` is nil to make
  misconfiguration failures easier to diagnose."
  [issuer audience jwks-uri]
  (if (nil? jwks-uri)
    (throw (ex-info "JWKS URI must be configured" {:issuer issuer :audience audience}))
    (let [provider (-> (JwkProviderBuilder. (URL. jwks-uri))
                      (.cached 10 24 TimeUnit/HOURS)
                      .build)]
      (fn [token]
        (let [jwt   (JWT/decode token)
              kid   (.getKeyId jwt)
              jwk   (.get provider kid)
              pub   (.getPublicKey jwk)
              algo  (Algorithm/RSA256 pub nil)
              verifier (-> (JWT/require algo)
                           (.withIssuer issuer)
                           (.withAudience (into-array String [audience]))
                           .build)]
          (.verify verifier token))))))

(defn- upsert-user!
  "Insert or update a user record and return the stored row."
  [db {:keys [idp-sub email name]}]
  (first
   (jdbc/query db
               ["insert into users as u (idp_sub,email,name) values (?,?,?) "
                "on conflict (idp_sub) do update set email=excluded.email, name=excluded.name "
                "returning u.id, u.email, u.idp_sub, u.last_used_org_id"
                idp-sub email name])))

(defn- update-last-org!
  [db user-id org-id]
  (jdbc/execute! db ["update users set last_used_org_id=? where id=?" org-id user-id]))

(defn- load-user-roles
  [db user-id org-id]
  (map :role
       (jdbc/query db ["select role from organization_members where user_id=? and org_id=?" user-id org-id])))

(defn wrap-auth
  "Middleware factory that validates bearer tokens and attaches an
  `:identity` map to the request on success.

  Options: `:issuer`, `:audience`, `:jwks-uri`, `:db`.  For testing a
  custom `:verifier` function may be supplied which should return a
  `DecodedJWT` when given a token."
  [{:keys [issuer audience jwks-uri verifier db]}]
  (let [verify (or verifier (build-verifier issuer audience jwks-uri))]
    (fn [handler]
      (fn [req]
        (if-let [token (bearer-token req)]
          (try
            (let [decoded (verify token)
                  claims  (decode-claims decoded)
                  idp-sub (:sub claims)
                  email   (:email claims)
                  name    (:name claims)
                  user    (upsert-user! db {:idp-sub idp-sub :email email :name name})
                  req-org (get-in req [:headers "x-org-id"])
                  org-id  (or req-org (:last_used_org_id user))
                  _       (when req-org (update-last-org! db (:id user) org-id))
                  roles   (let [token-roles (:roles claims)]
                            (if (seq token-roles)
                              (set (map keyword token-roles))
                              (if (and org-id (:id user))
                                (->> (load-user-roles db (:id user) org-id)
                                     (map keyword)
                                     set)
                                #{})))
                  identity {:user {:id (:id user)
                                   :email (:email user)
                                   :idp-sub (:idp_sub user)}
                            :org/id org-id
                            :roles roles}
                  resp    (handler (assoc req :identity identity))]
              (assoc resp :identity identity))
            (catch JWTVerificationException _
              (unauthorized "Invalid token"))
            (catch Exception e
              (println e)
              (http/internal-server-error {:error (.getMessage e)})))
          (unauthorized "Invalid token"))))))

(defn wrap-require-org
  "Middleware factory that ensures an org/id is present in the request
  identity and returns 403 otherwise."
  ([]
   (fn [handler]
     (fn [req]
       (if (get-in req [:identity :org/id])
         (handler req)
         (forbidden "Organization context required"))))))

(defn require-role
  "Middleware factory enforcing that the authenticated identity has the
  given role, using roles resolved from the database."
  [role]
  (fn [handler]
    (fn [req]
      (let [roles (get-in req [:identity :roles])
            role  (keyword role)]
        (if (contains? roles role)
          (handler req)
          (forbidden "Insufficient role"))))))

;; Public API exports
;; wrap-auth, wrap-require-org and require-role

