(ns etlp-mapper.organizations
  "Database access functions for organizations."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol Organizations
  (find-org [db id]
    "Find an organization by id.")
  (create-org [db data]
    "Create a new organization and return the inserted row.")
  (update-org [db id data]
    "Update organization by id with data map.")
  (delete-org [db id]
    "Delete organization by id.")
  (upsert-org [db data]
    "Insert or update organization by :id in data."))

(extend-protocol Organizations
  duct.database.sql.Boundary
  (find-org [{db :spec} id]
    (first (jdbc/query db ["select * from organizations where id = ?" id])))
  (create-org [{db :spec} data]
    (first (jdbc/insert! db :organizations data)))
  (update-org [{db :spec} id data]
    (jdbc/update! db :organizations data ["id = ?" id]))
  (delete-org [{db :spec} id]
    (jdbc/delete! db :organizations ["id = ?" id]))
  (upsert-org [db data]
    (if (find-org db (:id data))
      (update-org db (:id data) (dissoc data :id))
      (create-org db data))))

