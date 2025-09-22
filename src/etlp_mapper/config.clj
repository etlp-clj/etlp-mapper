(ns etlp-mapper.config
  "Configuration helpers for accessing environment driven settings."
  (:require [clojure.string :as str]))

(defn invite-secret
  "Return the invite token secret from the runtime environment.

  Falls back to the general APP_SECRET for compatibility when a
  dedicated invite secret is not supplied.  Blank values are treated as
  missing to avoid accidental misconfiguration."
  []
  (let [value (or (System/getenv "INVITE_TOKEN_SECRET")
                  (System/getProperty "INVITE_TOKEN_SECRET")
                  (System/getenv "APP_SECRET"))]
    (not-empty (some-> value str/trim))))
