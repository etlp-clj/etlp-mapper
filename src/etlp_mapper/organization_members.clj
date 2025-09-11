(ns etlp-mapper.organization-members
  "Database access for organization membership and helper checks."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol OrganizationMembers
  (find-members [db org-id]
    "Return all members for an organization.")
  (add-member [db data]
    "Add a membership record.")
  (remove-member [db org-id user-id]
    "Remove a user from an organization.")
  (member? [db org-id user-id]
    "Check if a user belongs to an organization.")
  (has-role? [db org-id user-id role]
    "Check if a user has a role in an organization."))

(extend-protocol OrganizationMembers
  duct.database.sql.Boundary
  (find-members [{db :spec} org-id]
    (jdbc/query db ["select * from organization_members where org_id = ?" org-id]))
  (add-member [{db :spec} data]
    (first (jdbc/insert! db :organization_members data)))
  (remove-member [{db :spec} org-id user-id]
    (jdbc/delete! db :organization_members ["org_id = ? and user_id = ?" org-id user-id]))
  (member? [{db :spec} org-id user-id]
    (-> (jdbc/query db ["select 1 from organization_members where org_id = ? and user_id = ? limit 1" org-id user-id])
        empty?
        not))
  (has-role? [{db :spec} org-id user-id role]
    (-> (jdbc/query db ["select 1 from organization_members where org_id = ? and user_id = ? and role = ? limit 1" org-id user-id role])
        empty?
        not)))

