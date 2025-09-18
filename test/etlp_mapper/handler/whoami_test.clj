(ns etlp-mapper.handler.whoami-test
  (:require [ataraxy.response :as response]
            [clojure.test :refer :all]
            [integrant.core :as ig]
            [etlp-mapper.handler.whoami]))

(deftest whoami-reads-identity
  (let [handler (ig/init-key :etlp-mapper.handler/whoami {})
        resp    (handler {:identity {:user {:id 1 :email "e" :idp-sub "s"}
                                     :roles #{:admin}
                                     :org/id "org-1"
                                     :claims {:exp 123}}})]
    (is (= ::response/ok (first resp)))
    (is (= {:id 1 :email "e" :idp-sub "s" :exp 123}
           (:user (second resp))))
    (is (= "org-1" (:org_id (second resp))))
    (is (= #{:admin} (:roles (second resp))))))
