(ns etlp-mapper.onboarding-test
  (:require [clojure.test :refer :all]))

(defonce db (atom {:orgs {} :invites {} :active {}}))

(use-fixtures :each (fn [f]
                      (reset! db {:orgs {} :invites {} :active {}})
                      (f)))

(defn create-org [name]
  (let [id (str "org-" (inc (count (:orgs @db))))]
    (swap! db assoc-in [:orgs id] {:id id :name name})
    id))

(defn send-invite [org-id email]
  (swap! db assoc-in [:invites email] {:org-id org-id :status :pending}))

(defn accept-invite [email]
  (swap! db update-in [:invites email] assoc :status :accepted))

(defn set-active-org [user-email org-id]
  (swap! db assoc-in [:active user-email] org-id))

(deftest onboarding-flow
  (let [org-id (create-org "Acme")
        _ (send-invite org-id "user@example.com")
        _ (accept-invite "user@example.com")
        _ (set-active-org "user@example.com" org-id)]
    (is (= org-id (get-in @db [:active "user@example.com"])))
    (is (= :accepted (get-in @db [:invites "user@example.com" :status])))))

