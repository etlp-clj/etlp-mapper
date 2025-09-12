(ns etlp-mapper.organization-subscriptions
  "Database access for organization subscriptions."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol OrganizationSubscriptions
  (find-subscription [db org-id]
    "Find a subscription for the organization.")
  (create-subscription [db data]
    "Create a new subscription record.")
  (update-subscription [db org-id data]
    "Update a subscription by organization id.")
  (upsert-subscription [db data]
    "Insert or update a subscription by :organization_id in data."))

(extend-protocol OrganizationSubscriptions
  duct.database.sql.Boundary
  (find-subscription [{db :spec} org-id]
    (first (jdbc/query db ["select * from organization_subscriptions where organization_id = ?" org-id])))
  (create-subscription [{db :spec} data]
    (first (jdbc/insert! db :organization_subscriptions data)))
  (update-subscription [{db :spec} org-id data]
    (jdbc/update! db :organization_subscriptions data ["organization_id = ?" org-id]))
  (upsert-subscription [db data]
    (if (find-subscription db (:organization_id data))
      (update-subscription db (:organization_id data) (dissoc data :organization_id))
      (create-subscription db data))))

