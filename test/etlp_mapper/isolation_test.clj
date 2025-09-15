(ns etlp-mapper.isolation-test
  (:require [clojure.test :refer :all]))

(defonce mappings (atom {}))
(defonce invites (atom {}))
(defonce members (atom #{}))
(defonce subscriptions (atom {}))
(defonce logs (atom {}))

(use-fixtures :each (fn [f]
                      (reset! mappings {1 {:org-id "org-1" :value "a"}
                                         2 {:org-id "org-2" :value "b"}})
                      (reset! invites {"tok-1" {:org-id "org-1"}
                                       "tok-2" {:org-id "org-2"}})
                      (reset! members #{["org-1" "user-1"]
                                        ["org-2" "user-2"]})
                      (reset! subscriptions {"org-1" {:plan "basic"}
                                             "org-2" {:plan "pro"}})
                      (reset! logs {1 {:org-id "org-1"}
                                    2 {:org-id "org-2"}})
                      (f)))

(defn fetch [org-id id]
  (let [row (get @mappings id)]
    (when (= org-id (:org-id row)) row)))

(defn fetch-invite [org-id token]
  (let [row (get @invites token)]
    (when (= org-id (:org-id row)) row)))

(defn member? [org-id user-id]
  (contains? @members [org-id user-id]))

(defn fetch-sub [org-id target-org]
  (when (= org-id target-org)
    (get @subscriptions target-org)))

(defn fetch-log [org-id id]
  (let [row (get @logs id)]
    (when (= org-id (:org-id row)) row)))

(deftest disallow-cross-org-access
  (is (nil? (fetch "org-1" 2)))
  (is (= {:org-id "org-1" :value "a"} (fetch "org-1" 1)))
  (is (nil? (fetch-invite "org-1" "tok-2")))
  (is (= {:org-id "org-1"} (fetch-invite "org-1" "tok-1")))
  (is (false? (member? "org-1" "user-2")))
  (is (true? (member? "org-1" "user-1")))
  (is (nil? (fetch-sub "org-1" "org-2")))
  (is (= {:plan "basic"} (fetch-sub "org-1" "org-1")))
  (is (nil? (fetch-log "org-1" 2)))
  (is (= {:org-id "org-1"} (fetch-log "org-1" 1))))

