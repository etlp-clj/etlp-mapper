(ns etlp-mapper.handler.me-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [integrant.core :as ig]
            [ataraxy.response :as response]
            ;; ensure handler namespace is loaded for init-key method
            [etlp-mapper.handler.me]))

(deftest set-active-org-persists-last-used-org-id
  (let [update-capture (atom nil)
        handler (ig/init-key :etlp-mapper.handler.me/set-active-org {:db {:spec ::db}})]
    (with-redefs [jdbc/query (fn [_ _] [{:organization_id "org-1"}])
                  jdbc/update! (fn [& args] (reset! update-capture args))]
      (let [resp (handler {:identity {:user {:id 1}}
                           :body-params {:org_id "org-1"}})]
        (is (= [::response/ok {:org_id "org-1"}] resp))
        (is (= [::db :users {:last_used_org_id "org-1"} ["id = ?" 1]]
               @update-capture))))))

(deftest set-active-org-forbidden-when-not-member
  (let [update-called? (atom false)
        handler (ig/init-key :etlp-mapper.handler.me/set-active-org {:db {:spec ::db}})]
    (with-redefs [jdbc/query (fn [_ _] [])
                  jdbc/update! (fn [& _] (reset! update-called? true))]
      (let [resp (handler {:identity {:user {:id 1}}
                           :body-params {:org_id "org-1"}})]
        (is (= ::response/forbidden (first resp)))
        (is (false? @update-called?))))))

