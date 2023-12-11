(ns etlp-mapper.handler.mappings
  (:require [ataraxy.response :as response]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [keywordize-keys]]
            duct.database.sql
            [integrant.core :as ig]
            [jute.core :as jt]
            [clojure.string :as s]
            [clj-http.client :as http]
            [yaml.core :as yaml]
            [duct.handler.sql :as sql]
            [cheshire.core :as json])
  (:import java.util.Base64))

(defprotocol Mappings
  (apply-mapping [db id data]))

(def parse-decoded-yml (fn [template]
                         (yaml/parse-string template :keywords true)))

(extend-protocol Mappings
  duct.database.sql.Boundary
  (apply-mapping [{db :spec} id data]
    (let [results (jdbc/query db [(str "select * from mappings "
                                       (format "where id='%s'" id))])
          template (-> results
                       first
                       :content
                       keywordize-keys
                       :yaml
                       parse-decoded-yml)
          compiled (jt/compile template)]
      (compiled data))))

(defn create [request]
  (let [yaml-content (slurp (:body request))
        parsed-data (yaml/parse-string yaml-content :keywords true)
        template (-> parsed-data :template)
        scope (-> parsed-data :scope)
        compiled (jt/compile template)]
    {:request (compiled scope)}))



(defn call-openai-api [prompt]
  (http/post "https://api.openai.com/v1/chat/completions"
             {:headers {"Content-Type" "application/json"
                        "Authorization" "Bearer foo"}
              :body prompt}))

(defn create-prompt [input-sample output-sample]
  (str "I want you to generate a Jute.clj template that maps the given input YAML data to the specified output YAML data. The task is to create a Jute.clj template using a YAML-based DSL similar to the example provided below. The example demonstrates a FizzBuzz implementation in Jute.clj YAML syntax:\n\n"
       "Example:\n"
       "```yaml\n"
       "$call: join-str\n"
       "$args:\n"
       "  - \" \"\n"
       "  - $map: $ range(0, 50, 1)\n"
       "    $as: num\n"
       "    $body:\n"
       "      $let:\n"
       "        - s: \"\"\n"
       "        - s:\n"
       "            $if: $ num % 3 = 0\n"
       "            $then: $ s + \"Fizz\"\n"
       "            $else: $ s\n"
       "        - s:\n"
       "            $if: $ num % 5 = 0\n"
       "            $then: $ s + \"Buzz\"\n"
       "            $else: $ s\n"
       "      $body:\n"
       "        $if: $ s = \"\"\n"
       "        $then: $ num\n"
       "        $else: $ toString(num) + \"-\" + s\n"
       "```\n\n"
       "Given the following input data in YAML format:\n\n"
       input-sample
       "\n\nGenerate a Jute.clj template that maps the input data to the following expected output in YAML format:\n\n"
       output-sample
       "\n\nPlease ensure that the template is valid Clojure code and adheres to the Jute.clj library conventions. The generated template should be efficient and easy to read."
       "\n\n---\n\n"))


(defn create-prompt-with-reduce [input-sample output-sample]
  (str "I want you to generate a Jute.clj template that maps the given input YAML data to the specified output YAML data. The task is to create a Jute.clj template using a YAML-based DSL similar to the example provided below. The example demonstrates the usage of the `$reduce` directive in Jute.clj YAML syntax, also, in the below example, JUTE template is mentioned under template key and scope contains the sample input the given template takes:\n\n"
       "Example:\n"
       "```yaml\n"
       "suite: $reduce directive\n"
       "tests:\n"
       "  - desc: does all the stuff which reduce function should do\n"
       "    scope:\n"
       "      names:\n"
       "        - \"Bob\"\n"
       "        - \"John\"\n"
       "        - \"Nick\"\n"
       "    template:\n"
       "      $reduce: $ names\n"
       "      $as: [\"acc\", \"name\"]\n"
       "      $start: \"\"\n"
       "      $body: $ acc + \"|\" + name\n"
       "    result: \"|Bob|John|Nick\"\n"
       "  - desc: works for objects too\n"
       "    scope:\n"
       "      items:\n"
       "        russia:\n"
       "          - omsk\n"
       "          - moscow\n"
       "          - saint-p\n"
       "        usa:\n"
       "          - orlando\n"
       "          - seattle\n"
       "          - springfield\n"
       "    template:\n"
       "      $reduce: $ items\n"
       "      $as: [\"acc\", \"pair\"]\n"
       "      $from: []\n"
       "      $body: $ concat(acc, pair.value)\n"
       "    result:\n"
       "      - omsk\n"
       "      - moscow\n"
       "      - saint-p\n"
       "      - orlando\n"
       "      - seattle\n"
       "      - springfield\n"
       "```\n\n"
       "Given the following input data in YAML format:\n\n"
       input-sample
       "\n\nGenerate a Jute.clj template that maps the input data to the following expected output in YAML format:\n\n"
       output-sample
       "\n\nPlease ensure that the template is valid JUTE YAML code and adheres to the Jute.clj library conventions. The generated template should be efficient and easy to read."
       "\n\n---\n\n"))


(defn depricated-react-prompt [query]
  (str "I want you to generate a Table component for the " query ". The Table component takes the following props:\n\n"
       "type,size,draggable,nestedRows,nestedRowRenderer,withHeader,headerOptions,withCheckbox,showMenu,withPagination,page,paginationType,pageSize,loaderSchema,multipleSorting,sortingList,filterList,errorTemplate,searchDebounceDuration,onRowClick,onSelect,onPageChange,headCellTooltip,separator,filterPosition,selectDisabledRow,className,data-test,fetchData\n\n"
       "Please ensure that the template is valid React code and adheres to the @innovaccer/design-system library conventions. The generated code should be efficient and easy to read.\n\n"
       "---\n"))

(defn create-react-prompt [query]
  (str "Using the @innovaccer/design-system library, generate a Table component specifically for displaying " query ". The Table component has the following props available:\n\n"
       "type, size, draggable, nestedRows, nestedRowRenderer, withHeader, headerOptions, withCheckbox, showMenu, withPagination, page, paginationType, pageSize, loaderSchema, multipleSorting, sortingList, filterList, errorTemplate, searchDebounceDuration, onRowClick, onSelect, onPageChange, headCellTooltip, separator, filterPosition, selectDisabledRow, className, data-test, fetchData\n\n"
       "Please create a code template that uses the appropriate props to fulfill the user's requirements. Ensure that the generated code is valid React code, adheres to the @innovaccer/design-system library conventions, and is efficient and easy to read.\n\n"
       "---\n"))



(defn generate-gpt-prompt [inputExample outputExample]
  (str ";; GPT Prompt for Generating JUTE Mapping\n"
       ";; ---------------------------------------\n"
       ";; Input: FHIR Resource (YAML)\n"
       ";; Output: Custom YAML Schema\n"
       ";; Requirement: Generate a JUTE template for the transformation\n\n"
       ";; FHIR Resource Example:\n"
       inputExample "\n\n"
       ";; Desired Output Schema:\n"
       outputExample "\n\n"
       ";; Instructions for GPT:\n"
       ";; Use only the supported JUTE functions like joinStr, concat, merge, etc.\n"
       ";; Ensure the JUTE template is in valid syntax and correctly maps the input to the desired output.\n"
       ";; Provide the JUTE template in Clojure syntax.\n\n"
       ";; GPT, please generate the JUTE template:\n"))


(defn generate-gpt-json-payload [inputExample outputExample]
  (let [prompt (str ";; GPT Prompt for Generating JUTE Mapping\n"
                    ";; ---------------------------------------\n"
                    ";; Input: FHIR Resource YAML\n"
                    ";; Output: Custom YAML Schema\n"
                    ";; Requirement: Generate a JUTE template for the transformation\n\n"
                    ";; FHIR Resource Example:\n"
                    inputExample "\n\n"
                    ";; Desired Output Schema:\n"
                    outputExample "\n\n"
                    ";; Instructions for GPT:\n"
                    ";; Use only the supported JUTE functions like joinStr, concat, merge, etc.\n"
                    ";; Ensure the JUTE template is in valid syntax and correctly maps the input to the desired output.\n"
                    ";; GPT, please generate the JUTE template:\n")]
    (json/encode
      {:model "gpt-4-turbo"
       :messages [{:role "system" :content "You are a helpful data mapping assistant."}
                  {:role "user" :content prompt}]})))

(defn extract-jute-template [response]
  (let [text (:text (first (:choices response)))]
    (s/trim text)))

(defn suggest-mapping [request]
  (let [yaml-content (slurp (:body request))
        parsed-data (yaml/parse-string yaml-content :keywords true)
        template (-> parsed-data :result)
        scope (-> parsed-data :scope)
        prompt (generate-gpt-json-payload (yaml/generate-string scope) (yaml/generate-string template))
        result (call-openai-api prompt)]
    {:result result}))

(defn suggest-component [request]
  (let [yaml-content (slurp (:body request))
        parsed-data (yaml/parse-string yaml-content :keywords true)
        scope (-> parsed-data :scope)
        prompt (create-react-prompt (yaml/generate-string scope))
        result (call-openai-api prompt)]
    {:result result}))



(defmethod ig/init-key :etlp-mapper.handler/openai [_ {:keys [db]}]
  (fn [opts]
    (try
      (let [translated (suggest-mapping opts)  resp (json/decode (get-in translated [:result :body]) true)]
        (pprint resp)
        [::response/ok resp])
      (catch Exception e
        (println e)
        [::response/bad-request {:error (.getMessage e)}]))))


(defmethod ig/init-key :etlp-mapper.handler/apply-mappings [_ {:keys [db]}]
  (fn [{[_ id data] :ataraxy/result}]
    (try
      (let [translated (apply-mapping db id data)]
        [::response/ok {:result translated}])
      (catch Exception e
        (println e)
        [::response/bad-request {:error (str e)}]))))


(defmethod ig/init-key :etlp-mapper.handler/mappings [_ {:keys [db]}]
  (fn [opts]
    (try
      (let [translated (create opts)] 
        (pprint translated)
        [::response/ok translated])
      (catch Exception e
        (println e)
        [::response/bad-request {:error (.getMessage e)}]))))
