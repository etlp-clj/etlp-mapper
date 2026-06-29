(ns etlp-mapper.sqlite-test
  "Round-trip integration test for the SQLite backend against a real temp
   database file: schema DDL, the JSON-marshalling shim
   (etlp-mapper.sqlitetypes), the history + updated_at triggers, and a Jute
   apply through duct.database.sql.Boundary (the same path the HTTP handler
   uses, minus auth/jetty)."
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [cheshire.core :as json]
            [duct.database.sql]
            [duct.handler.sql :as dhsql]
            [etlp-mapper.sqlitetypes]              ; loads the JDBC protocol shim + insert! override
            [etlp-mapper.handler.mappings :as mappings])
  (:import [java.io File]))

(def ^:private ddl
  ;; Mirror of the SQLite migration set in resources/etlp_mapper/config.edn
  ;; (:duct.profile/sqlite). Kept in sync by hand; this test fails loudly if
  ;; the trigger/marshalling semantics drift.
  ["CREATE TABLE mappings (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, content TEXT, org_id TEXT NOT NULL DEFAULT 'default', created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')))"
   "CREATE TABLE mappings_history (id INTEGER PRIMARY KEY AUTOINCREMENT, original_id INTEGER, txnid TEXT, title TEXT NOT NULL, content TEXT, org_id TEXT NOT NULL DEFAULT 'default', created_at TEXT, updated_at TEXT)"
   "CREATE TRIGGER insert_mapping_history_trigger BEFORE UPDATE ON mappings FOR EACH ROW WHEN NEW.content IS NOT OLD.content OR NEW.title IS NOT OLD.title BEGIN INSERT INTO mappings_history (title, content, created_at, updated_at, original_id, org_id, txnid) VALUES (OLD.title, OLD.content, OLD.created_at, OLD.updated_at, OLD.id, OLD.org_id, strftime('%Y%m%d%H%M%f','now') || '-' || lower(hex(randomblob(4)))); END"
   "CREATE TRIGGER update_mapping_changetimestamp AFTER UPDATE ON mappings FOR EACH ROW WHEN NEW.content IS NOT OLD.content OR NEW.title IS NOT OLD.title BEGIN UPDATE mappings SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id = NEW.id; END"])

(defn- temp-db-spec []
  (let [f (File/createTempFile "etlp-sqlite-test" ".db")]
    (.deleteOnExit f)
    {:connection-uri (str "jdbc:sqlite:" (.getAbsolutePath f))}))

(deftest sqlite-roundtrip
  (let [db (temp-db-spec)]
    (doseq [stmt ddl] (jdbc/execute! db [stmt]))

    (testing "map content is stored as JSON TEXT and decoded back to a map"
      (jdbc/insert! db :mappings {:title   "demo"
                                  :content {:yaml "patientId: data.id"}
                                  :org_id  "acme"})
      (let [row (first (jdbc/query db ["SELECT * FROM mappings WHERE org_id = ?" "acme"]))]
        (is (= "demo" (:title row)))
        (is (map? (:content row)) "content decoded to a Clojure map on read")
        (is (= "patientId: data.id" (get (:content row) "yaml"))))
      ;; the raw column really is JSON TEXT (aliased so the decoder leaves it alone)
      (let [raw (-> (jdbc/query db ["SELECT content AS c FROM mappings WHERE org_id = ?" "acme"])
                    first :c)]
        (is (string? raw))
        (is (= {"yaml" "patientId: data.id"} (json/parse-string raw)))))

    (testing "UPDATE fires the BEFORE-UPDATE history trigger and bumps updated_at"
      (let [before (-> (jdbc/query db ["SELECT updated_at FROM mappings WHERE org_id = ?" "acme"])
                       first :updated_at)
            _      (Thread/sleep 5)]            ; ensure a distinct ms for updated_at
        (jdbc/execute! db ["UPDATE mappings SET content = ? WHERE org_id = ?"
                           {:yaml "patientId: data.patient"} "acme"])
        (let [hist (jdbc/query db ["SELECT * FROM mappings_history"])
              now  (-> (jdbc/query db ["SELECT updated_at FROM mappings WHERE org_id = ?" "acme"])
                       first :updated_at)]
          (is (= 1 (count hist)) "exactly one history row (no recursive double-write)")
          (is (some? (:txnid (first hist))) "txnid surrogate populated by the trigger")
          (is (= "patientId: data.id" (get (:content (first hist)) "yaml"))
              "history captured the PRE-update content")
          (is (not= before now) "updated_at was bumped by the AFTER-UPDATE trigger"))))

    (testing "apply-mapping compiles the stored Jute template through the Boundary"
      (let [id       (-> (jdbc/query db ["SELECT id FROM mappings WHERE org_id = ?" "acme"])
                         first :id)
            _        (jdbc/execute! db ["UPDATE mappings SET content = ? WHERE id = ?"
                                        {:yaml "status: active"} id])
            boundary (duct.database.sql/->Boundary db)
            result   (mappings/apply-mapping boundary "acme" id {:any "payload"})]
        (is (= {:status "active"} result)
            "decoded content -> keywordize -> :yaml -> Jute compile -> apply")))))

(deftest insert-returns-id-for-location
  ;; duct.handler.sql/insert! must yield a map containing :id so the create
  ;; handler's `:location "mappings/{id}"` resolves. SQLite's
  ;; db-do-prepared-return-keys natively returns {(keyword "last_insert_rowid()") N};
  ;; etlp-mapper.sqlitetypes re-binds insert! to also expose :id.
  (let [db       (temp-db-spec)
        boundary (duct.database.sql/->Boundary db)]
    (jdbc/execute! db ["CREATE TABLE mappings (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, content TEXT, org_id TEXT NOT NULL DEFAULT 'default')"])
    (let [k1 (dhsql/insert! boundary ["INSERT INTO mappings (title, content, org_id) VALUES (?,?,?)" "a" "{}" "acme"])
          k2 (dhsql/insert! boundary ["INSERT INTO mappings (title, content, org_id) VALUES (?,?,?)" "b" "{}" "acme"])]
      (is (= 1 (:id k1)) "first insert exposes :id=1 (not just :last_insert_rowid())")
      (is (= 2 (:id k2)) ":id tracks the autoincrement rowid")
      ;; query/execute! still work after re-extending the protocol
      (is (= 2 (count (dhsql/query boundary ["SELECT * FROM mappings"]))))
      (is (vector? (dhsql/execute! boundary ["UPDATE mappings SET title='x' WHERE id=1"]))))))
