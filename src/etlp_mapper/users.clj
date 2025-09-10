(ns etlp-mapper.users
  "Database access functions for users."
  (:require [clojure.java.jdbc :as jdbc]
            duct.database.sql))

(defprotocol Users
  (find-user [db id]
    "Find user by id.")
  (find-user-by-email [db email]
    "Find user by email address.")
  (create-user [db data]
    "Create a new user.")
  (update-user [db id data]
    "Update a user with given id and data map.")
  (delete-user [db id]
    "Delete user by id.")
  (upsert-user [db data]
    "Insert or update a user by :id in data."))

(extend-protocol Users
  duct.database.sql.Boundary
  (find-user [{db :spec} id]
    (first (jdbc/query db ["select * from users where id = ?" id])))
  (find-user-by-email [{db :spec} email]
    (first (jdbc/query db ["select * from users where email = ?" email])))
  (create-user [{db :spec} data]
    (first (jdbc/insert! db :users data)))
  (update-user [{db :spec} id data]
    (jdbc/update! db :users data ["id = ?" id]))
  (delete-user [{db :spec} id]
    (jdbc/delete! db :users ["id = ?" id]))
  (upsert-user [db data]
    (if (find-user db (:id data))
      (update-user db (:id data) (dissoc data :id))
      (create-user db data))))

