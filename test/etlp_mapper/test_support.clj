(ns etlp-mapper.test-support
  "Shared helpers and fixtures for database aware tests."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [etlp-mapper.db :as db]))

(defonce ^:private test-spec* (atom nil))

(defn- h2-spec []
  {:classname "org.h2.Driver"
   :connection-uri "jdbc:h2:mem:etlp_mapper_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})

(defn test-spec []
  (or @test-spec*
      (reset! test-spec*
              (if-let [url (or (System/getenv "TEST_JDBC_URL")
                                (System/getenv "JDBC_URL")
                                (System/getProperty "TEST_JDBC_URL"))]
                {:connection-uri url}
                (h2-spec)))))

(defn execute!
  "Execute a SQL statement against the shared test datasource."
  ([sql]
   (execute! sql []))
  ([sql params]
   (let [statement (if (vector? sql)
                     sql
                     (into [sql] params))]
     (jdbc/execute! (test-spec) statement))))

(defn with-test-datasource [f]
  (let [spec (test-spec)
        uri  (:connection-uri spec)
        original-secret (System/getProperty "INVITE_TOKEN_SECRET")]
    (when (and uri (str/starts-with? uri "jdbc:h2"))
      (Class/forName "org.h2.Driver"))
    (System/setProperty "INVITE_TOKEN_SECRET" "test-secret")
    (with-redefs [db/get-datasource (constantly spec)]
      (db/set-datasource! spec)
      (try
        (f)
        (finally
          (db/set-datasource! nil)
          (reset! test-spec* nil)
          (if original-secret
            (System/setProperty "INVITE_TOKEN_SECRET" original-secret)
            (System/clearProperty "INVITE_TOKEN_SECRET")))))))
