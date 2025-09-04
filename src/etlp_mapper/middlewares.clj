(ns etlp-mapper.middlewares
  (:require
    [ring.middleware.cors :refer [wrap-cors]]
   [ataraxy.core :as ataraxy]
   [ataraxy.response :as response]
   [integrant.core :as ig]))

(defn dead-cors-middleware

  "Allow cross-origin requests for common HTTP methods and headers."
  [handler]
  (wrap-cors handler
             :access-control-allow-origin [#".*"]
             :access-control-allow-methods [:get :post :put :delete :options]
             :access-control-allow-headers ["Content-Type" "Authorization"]))

(defn cors-middleware
  "Middleware that allows CORS requests and handles preflight OPTIONS."
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status  200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type, Authorization"}
       :body    "OK"}
      (let [response (handler request)]
        (-> response
            (assoc-in [:headers "Access-Control-Allow-Origin"] "*")
            (assoc-in [:headers "Access-Control-Allow-Methods"] "GET, POST, PUT, DELETE, OPTIONS")
            (assoc-in [:headers "Access-Control-Allow-Headers"] "Content-Type, Authorization")
            (assoc-in [:headers "Access-Control-Expose-Headers"] "Location"))))))


(defmethod ig/init-key :etlp-mapper.middlewares/cors
  [_ _]
  cors-middleware)
