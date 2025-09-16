(ns etlp-mapper.handler.orgs-test
  (:require [clojure.test :refer :all]
            [integrant.core :as ig]
            [ring.mock.request :as mock]
            [etlp-mapper.handler.orgs]
            [etlp-mapper.onboarding :as onboarding]
            [etlp-mapper.audit-logs :as audit-logs]
            [etlp-mapper.ai-usage-logs :as ai-usage-logs]))

(deftest post-orgs-idempotent
  (let [store (atom {})
        ensure (fn [_ _ {:keys [name]}]
                 (if-let [existing (some (fn [[id org]] (when (= (:name org) name) id)) @store)]
                   existing
                   (let [id (str "org-" (inc (count @store)))]
                     (swap! store assoc id {:id id :name name})
                     id)))
        handler (ig/init-key :etlp-mapper.handler.orgs/create {:db {} :kc {}})]
    (with-redefs [onboarding/ensure-org! ensure
                  audit-logs/log! (fn [& _] nil)
                  ai-usage-logs/log! (fn [& _] nil)]
      (let [req (-> (mock/request :post "/orgs" {:name "Acme"})
                    (assoc :identity {:user {:id "user-1"}})
                    (assoc :body-params {:name "Acme"}))
            [_ body1] (handler req)
            [_ body2] (handler req)]
        (is (= body1 body2))
        (is (= 1 (count @store)))))))
