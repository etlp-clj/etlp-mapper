(ns etlp-mapper.handler.parse-hl7
  (:require [ataraxy.response :as response]
            [integrant.core :as ig]
            [clojure.string :as str]
            [etlp-hl7v2.core :as hl7]))

(defn- remove-nil-keys
  "Recursively remove nil keys from maps. Jackson cannot serialize null map keys to JSON."
  [x]
  (cond
    (map? x) (into {} (for [[k v] x :when (some? k)] [k (remove-nil-keys v)]))
    (sequential? x) (mapv remove-nil-keys x)
    :else x))

(defn- extract-z-segments
  "Extract Z-segments generically from raw HL7 message text.
   Splits each Z-segment line by | and groups by segment ID.
   Returns {\"ZPD\" [[\"field1\" \"field2\" ...]] \"ZAL\" [[...]]}."
  [raw-msg]
  (let [lines (->> (str/split raw-msg #"\r\n|\r|\n")
                   (map str/trim)
                   (filter #(re-matches #"Z[A-Z0-9].*" %)))]
    (reduce (fn [acc line]
              (let [fields (str/split line #"\|")
                    seg-name (first fields)]
                (update acc seg-name (fnil conj []) (vec (rest fields)))))
            {}
            lines)))

(defn- normalize-delimiters
  "Normalize literal escape sequences (\\r \\n) to actual carriage returns
   so the HL7 parser can split segments correctly."
  [message]
  (-> message
      (str/replace "\\r\\n" "\r")
      (str/replace "\\r" "\r")
      (str/replace "\\n" "\n")))

(defn- extract-present-segments
  "Extract the list of segment names present in the raw message."
  [message]
  (->> (str/split message #"\r\n|\r|\n")
       (map str/trim)
       (filter #(> (.length %) 0))
       (mapv #(re-find #"^[A-Z][A-Z0-9]{2}" %))
       (filterv some?)))

(defmethod ig/init-key :etlp-mapper.handler/parse-hl7 [_ _]
  (fn [{[_ message] :ataraxy/result :as request}]
    (let [extensions (get-in request [:body-params :extensions])
          strict? (get-in request [:body-params :strict] false)]
      (if (or (nil? message) (str/blank? message))
        [::response/ok {:valid false
                        :parsed nil
                        :z_segments {}
                        :message_type nil
                        :message_control_id nil
                        :segments_found []
                        :conformance {:valid false
                                      :warnings [{:type "VALIDATION_ERROR"
                                                   :message "Missing or empty HL7 message"}]}
                        :errors [{:message "Missing or empty HL7 message"
                                  :type "VALIDATION_ERROR"}]}]
        (try
          (let [exts (or extensions [])
                message (normalize-delimiters message)
                segments-found (extract-present-segments message)
                ;; Use validate for comprehensive conformance checking
                validation (hl7/validate message {:extensions exts})
                parsed-result (:result validation)
                conformance-warnings (:warnings validation)
                spec-valid (:valid validation)]
            (if (nil? parsed-result)
              [::response/ok {:valid false
                              :parsed nil
                              :z_segments {}
                              :message_type nil
                              :message_control_id nil
                              :segments_found segments-found
                              :conformance {:valid false
                                            :warnings conformance-warnings}
                              :errors (mapv (fn [w] {:message (:message w) :type (:type w)})
                                            conformance-warnings)}]
              ;; In strict mode, reject if conformance check failed
              (if (and strict? (not spec-valid))
                [::response/bad-request
                 {:valid false
                  :parsed nil
                  :z_segments {}
                  :message_type nil
                  :message_control_id nil
                  :segments_found segments-found
                  :conformance {:valid false
                                :warnings conformance-warnings}
                  :errors (mapv (fn [w] {:message (:message w) :type (:type w)})
                                conformance-warnings)}]
                (let [parsed (remove-nil-keys parsed-result)
                      msg-type (let [{:keys [code event]} (get-in parsed [:MSH :type])]
                                 (if event (str code "^" event) code))
                      msg-id (get-in parsed [:MSH :id])
                      raw-z (extract-z-segments message)
                      parsed-z-keys (set (map name (filter #(str/starts-with? (name %) "Z")
                                                           (keys parsed))))
                      z-segments (apply dissoc raw-z parsed-z-keys)
                      grammar-spec (when msg-type
                                     (let [[code event] (str/split msg-type #"\^")]
                                       (hl7/get-grammar-spec code event {:extensions exts})))]
                  [::response/ok {:valid true
                                  :parsed parsed
                                  :z_segments z-segments
                                  :message_type msg-type
                                  :message_control_id msg-id
                                  :segments_found segments-found
                                  :conformance {:valid spec-valid
                                                :message_structure (when grammar-spec
                                                                     (:grammar_key grammar-spec))
                                                :expected_segments (when grammar-spec
                                                                     (get-in grammar-spec [:rules "msg"]))
                                                :warnings conformance-warnings}
                                  :errors []}]))))
          (catch Exception e
            [::response/ok {:valid false
                            :parsed nil
                            :z_segments {}
                            :message_type nil
                            :message_control_id nil
                            :segments_found []
                            :conformance {:valid false
                                          :warnings [{:type "PARSE_ERROR"
                                                       :message (.getMessage e)}]}
                            :errors [{:message (.getMessage e)
                                      :type "PARSE_ERROR"}]}]))))))
