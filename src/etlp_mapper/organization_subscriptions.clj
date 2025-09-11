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
    "Insert or update a subscription by :org_id in data."))

(extend-protocol OrganizationSubscriptions
  duct.database.sql.Boundary
  (find-subscription [{db :spec} org-id]
    (first (jdbc/query db ["select * from organization_subscriptions where org_id = ?" org-id])))
  (create-subscription [{db :spec} data]
    (first (jdbc/insert! db :organization_subscriptions data)))
  (update-subscription [{db :spec} org-id data]
    (jdbc/update! db :organization_subscriptions data ["org_id = ?" org-id]))
  (upsert-subscription [db data]
    (if (find-subscription db (:org_id data))
      (update-subscription db (:org_id data) (dissoc data :org_id))
      (create-subscription db data))))

