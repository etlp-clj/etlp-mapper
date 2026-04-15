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

(def allowed-origins
  #{"http://localhost:5173"
    "http://127.0.0.1:5173"
    "http://192.168.1.21:5173"
    "http://localhost:8000"
    "http://127.0.0.1:8000"
    "http://192.168.1.21:8000"})

(defn- allowed-origin?
  [origin]
  (contains? allowed-origins origin))

(defn- cors-headers
  [origin]
  {"Access-Control-Allow-Origin"      origin
   "Access-Control-Allow-Methods"     "GET, POST, PUT, DELETE, OPTIONS"
   "Access-Control-Allow-Headers"     "Content-Type, Authorization"
   "Access-Control-Allow-Credentials" "true"
   "Access-Control-Expose-Headers"    "Location"
   "Vary"                             "Origin"})

(defn cors-middleware
  "Middleware that allows CORS requests and handles preflight OPTIONS."
  [handler]
  (fn [request]
    (let [origin (get-in request [:headers "origin"])]
      (if (= :options (:request-method request))
        (if (allowed-origin? origin)
          {:status  200
           :headers (cors-headers origin)
           :body    "OK"}
          {:status 403
           :body "CORS origin not allowed"})
        (let [response (handler request)]
          (if (allowed-origin? origin)
            (update response :headers merge (cors-headers origin))
            response))))))

(defmethod ig/init-key :etlp-mapper.middlewares/cors
  [_ _]
  cors-middleware)
