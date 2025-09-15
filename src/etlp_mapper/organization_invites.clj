(ns etlp-mapper.organization-invites
  "Database access and token helpers for organization invites."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql)
  (:import (com.auth0.jwt JWT)
           (com.auth0.jwt.algorithms Algorithm)))

(defprotocol OrganizationInvites
  (find-invite [db org-id token]
    "Find an invite by token scoped to an organization.")
  (create-invite [db data]
    "Create a new invite record.")
  (upsert-invite [db data]
    "Insert or update an invite by token.")
  (consume-invite [db org-id token]
    "Delete an invite by token within an organization."))

(defn sign-token
  "Sign invite claims with a shared secret and return a JWT string."
  [secret claims]
  (let [algo (Algorithm/HMAC256 secret)
        builder (reduce (fn [b [k v]] (.withClaim b (name k) (str v)))
                        (JWT/create)
                        claims)]
    (.sign builder algo)))

(defn verify-token
  "Verify an invite token using the shared secret.
   Returns the claims map or nil if verification fails."
  [secret token]
  (let [algo (Algorithm/HMAC256 secret)
        verifier (-> (JWT/require algo) .build)]
    (try
      (let [decoded (.verify verifier token)
            claims (.getClaims decoded)]
        (into {}
              (for [[k v] claims]
                [(keyword k) (.asString v)])))
      (catch Exception _ nil))))

(extend-protocol OrganizationInvites
  duct.database.sql.Boundary
  (find-invite [{db :spec} org-id token]
    (first (jdbc/query db
                       ["select * from organization_invites where token = ? and organization_id = ?" token org-id])))
  (create-invite [{db :spec} data]
    (first (jdbc/insert! db :organization_invites data)))
  (upsert-invite [db {:keys [organization_id] :as data}]
    (if (find-invite db organization_id (:token data))
      (jdbc/update! (:spec db) :organization_invites (dissoc data :token :organization_id)
                    ["token = ? and organization_id = ?" (:token data) organization_id])
      (create-invite db data)))
  (consume-invite [{db :spec} org-id token]
    (jdbc/delete! db :organization_invites
                  ["token = ? and organization_id = ?" token org-id])))

