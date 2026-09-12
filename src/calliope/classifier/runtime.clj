(ns calliope.classifier.runtime
  "JVM interpreter for Calliope classifier programs.

  The DSL remains pure data. This namespace supplies explicit adapters for
  filesystem sources, deterministic context transforms, Ollama/llama.cpp model
  calls, output validation, exact feature caching, and append-only EDN events."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [calliope.classifier.dsl :as dsl]
            [malli.core :as m]
            [malli.json-schema :as json-schema]
            [malli.transform :as mt])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Random UUID]))

(defn sha256
  "Return a lowercase SHA-256 digest for a string or byte array."
  [value]
  (let [bytes (if (string? value)
                (.getBytes ^String value StandardCharsets/UTF_8)
                value)
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn- resolve-file
  [base-dir path]
  (let [file (io/file path)]
    (if (.isAbsolute file)
      file
      (io/file base-dir path))))

(defn default-runtime
  "Create the concrete JVM adapter set. Individual functions may be overridden
  in tests or alternate hosts."
  ([] (default-runtime "."))
  ([base-dir]
   {:base-dir base-dir
    :read-edn (fn [path]
                (-> (resolve-file base-dir path) slurp edn/read-string))
    :read-text (fn [path]
                 (slurp (resolve-file base-dir path)))
    :read-ledger
    (fn [path]
      (let [file (resolve-file base-dir path)]
        (if (.exists file)
          (with-open [reader (io/reader file)]
            (->> (line-seq reader)
                 (remove str/blank?)
                 (mapv edn/read-string)))
          [])))
    :append-event!
    (fn [path event]
      (let [file (resolve-file base-dir path)]
        (io/make-parents file)
        (spit file (str (pr-str event) "\n") :append true)))
    :now #(str (Instant/now))
    :uuid #(str (UUID/randomUUID))}))

(defn- runtime-fn
  [runtime key]
  (or (get runtime key)
      (throw (ex-info "Runtime adapter is missing."
                      {:adapter/key key}))))

(defn- projection->objects
  [source projection]
  (mapv
   (fn [[object-id value]]
     (assoc value
            :object/id (str object-id)
            :object/type (:source/object-type source)))
   projection))

(defn load-source
  "Load one declared source into normalized object maps."
  [runtime source]
  (case (:source/type source)
    :edn-projection
    (projection->objects source
                         ((runtime-fn runtime :read-edn)
                          (:source/location source)))

    :edn-ledger
    (mapv #(assoc % :object/type (:source/object-type source))
          ((runtime-fn runtime :read-ledger)
           (:source/location source)))

    (throw (ex-info "Source type is not implemented by the JVM interpreter."
                    {:source/id (:source/id source)
                     :source/type (:source/type source)}))))

(defn matches-filter?
  "Interpret the DSL's deliberately small filter language."
  [object expression]
  (if-not expression
    true
    (let [[op & args] expression]
      (case op
        :eq (= (get object (first args)) (second args))
        :not-eq (not= (get object (first args)) (second args))
        :contains
        (let [[field expected] args
              value (get object field)]
          (cond
            (map? value) (contains? value expected)
            (set? value) (contains? value expected)
            (string? value) (str/includes? value (str expected))
            (coll? value) (boolean (some #(= expected %) value))
            :else false))
        :present (contains? object (first args))
        :not (not (matches-filter? object (first args)))
        :and (every? #(matches-filter? object %) (first args))
        :or (boolean (some #(matches-filter? object %) (first args)))
        (throw (ex-info "Unknown filter operation."
                        {:filter/op op :filter/expression expression}))))))

(defn- distinct-by
  [field objects]
  (if-not field
    (vec objects)
    (loop [remaining objects
           seen #{}
           result []]
      (if-let [object (first remaining)]
        (let [value (get object field)]
          (if (contains? seen value)
            (recur (rest remaining) seen result)
            (recur (rest remaining) (conj seen value) (conj result object))))
        result))))

(defn seeded-shuffle
  "Fisher-Yates shuffle with a reproducible java.util.Random seed."
  [values seed]
  (let [array (object-array values)
        random (Random. (long seed))]
    (loop [index (dec (alength array))]
      (when (pos? index)
        (let [other (.nextInt random (inc index))
              current (aget array index)]
          (aset array index (aget array other))
          (aset array other current)
          (recur (dec index)))))
    (vec array)))

(defn execute-selector
  "Select objects according to one compiled selector definition."
  [objects selector {:keys [run-seed inputs]}]
  (let [filtered (filterv #(matches-filter? % (:selector/where selector)) objects)
        seed (if (= :run-seed (:selector/seed selector))
               run-seed
               (:selector/seed selector))]
    (case (:selector/type selector)
      :random
      (->> filtered
           (distinct-by (:selector/distinct-by selector))
           (#(seeded-shuffle % seed))
           (take (:selector/count selector))
           vec)

      :stratified-random
      (->> (:selector/strata selector)
           (map-indexed
            (fn [index {:keys [stratum/value stratum/count]}]
              (->> filtered
                   (filter #(= value (get % (:selector/field selector))))
                   (distinct-by (:selector/distinct-by selector))
                   (#(seeded-shuffle % (+ (long seed) index)))
                   (take count))))
           (apply concat)
           vec)

      :explicit
      (let [by-id (into {} (map (juxt :object/id identity)) filtered)]
        (mapv
         (fn [object-id]
           (or (get by-id object-id)
               (throw
                (ex-info "Explicit selector references an unknown or filtered-out object."
                         {:selector/id (:selector/id selector)
                          :object/id object-id}))))
         (:selector/object-ids selector)))

      :provided
      (->> (get inputs (:selector/input-key selector) [])
           (filter #(matches-filter? % (:selector/where selector)))
           (take (:selector/max-count selector))
           vec)

      :anchor-neighbors
      (throw (ex-info "Anchor-neighbor selection requires a retrieval adapter."
                      {:selector/id (:selector/id selector)}))

      (throw (ex-info "Unknown selector type."
                      {:selector/type (:selector/type selector)})))))

(defn- parse-header
  [text]
  (let [lines (str/split-lines text)
        title (some #(second (re-matches #"(?i)^Title:\s*(.+)$" %)) lines)
        prompt-index (first (keep-indexed
                             (fn [index line]
                               (when (re-matches #"(?i)^Prompt:\s*$" line)
                                 index))
                             lines))
        lyric-index (first (keep-indexed
                            (fn [index line]
                              (when (re-find #"(?i)lyrics" line)
                                index))
                            lines))
        prompt (when prompt-index
                 (->> (subvec (vec lines)
                              (inc prompt-index)
                              (or lyric-index (count lines)))
                      (drop-while str/blank?)
                      (str/join "\n")
                      str/trim))]
    {:header/title title
     :style-and-metadata (str/trim
                          (str (when title (str "Title: " title "\n"))
                               (when (seq prompt) (str "Prompt:\n" prompt))))}))

(defn hydrate-canonical-lyric
  [runtime object]
  (let [text ((runtime-fn runtime :read-text) (:file object))
        header (parse-header text)]
    (merge object
           header
           {:title (or (:title object) (:header/title header) (:object/id object))
            :lyrics text
            :object/content-sha256
            (or (:body-sha256 object) (sha256 text))})))

(defn- section-type
  [label]
  (let [normalized (-> label str/lower-case (str/replace #"[^a-z0-9]+" " "))]
    (cond
      (str/includes? normalized "pre chorus") :pre-chorus
      (str/includes? normalized "chorus") :chorus
      (str/includes? normalized "refrain") :refrain
      (str/includes? normalized "hook") :hook
      (str/includes? normalized "verse") :verse
      (str/includes? normalized "bridge") :bridge
      (str/includes? normalized "intro") :intro
      (str/includes? normalized "outro") :outro
      (str/includes? normalized "interlude") :interlude
      (str/includes? normalized "instrumental") :instrumental
      (str/includes? normalized "spoken") :spoken
      (str/includes? normalized "coda") :coda
      :else :unknown)))

(def section-marker-pattern
  #"^\s*(?:#{1,6}\s*)?(?:\*\*)?\[([^\]]+)\](?:\*\*)?\s*$")

(defn explicit-sections
  "Parse bracketed lyric section markers while preserving line spans."
  [text]
  (let [lines (vec (str/split-lines text))
        starts (->> lines
                    (keep-indexed
                     (fn [index line]
                       (when-let [[_ label] (re-matches section-marker-pattern line)]
                         {:index index :label label})))
                    vec)]
    (if (seq starts)
      (mapv
       (fn [ordinal {:keys [index label]}]
         (let [next-index (or (:index (nth starts (inc ordinal) nil))
                              (count lines))
               body-lines (subvec lines (inc index) next-index)
               body (->> body-lines (str/join "\n") str/trim)]
           {:section/id (str ordinal "-" (name (section-type label)))
            :section/type (section-type label)
            :section/label label
            :section/ordinal ordinal
            :section/start-line (inc index)
            :section/end-line next-index
            :section/text (if (seq body) body label)}))
       (range (count starts))
       starts)
      [{:section/id "0-unknown"
        :section/type :unknown
        :section/label "Unsegmented"
        :section/ordinal 0
        :section/start-line 1
        :section/end-line (max 1 (count lines))
        :section/text text}])))

(defn song-sections-result
  [object]
  {:object/id (:object/id object)
   :feature/id :calliope/song-sections-v1
   :feature/value (explicit-sections (:lyrics object))})

(def built-in-resolvers
  {:calliope/canonical-lyric-v1 hydrate-canonical-lyric
   :calliope/lyric-and-production-metadata-v1 hydrate-canonical-lyric
   :calliope/explicit-song-sections-v1
   (fn [_ object] (song-sections-result object))})

(defn- resolver
  [runtime resolver-id]
  (or (get-in runtime [:resolvers resolver-id])
      (get built-in-resolvers resolver-id)
      (throw (ex-info "Context or extractor resolver is not registered."
                      {:resolver/id resolver-id}))))

(defn- truncate-string
  [value max-chars overflow]
  (if (<= (count value) max-chars)
    value
    (case overflow
      :truncate-tail (subs value 0 max-chars)
      :truncate-middle
      (let [left (quot max-chars 2)
            right (- max-chars left)]
        (str (subs value 0 left) "\n…\n"
             (subs value (- (count value) right))))
      value)))

(defn- limit-object
  [object max-chars overflow]
  (if (= :drop-object overflow)
    (when (<= (count (pr-str object)) max-chars) object)
    (cond-> object
      (string? (:lyrics object))
      (update :lyrics truncate-string max-chars overflow)

      (string? (:style-and-metadata object))
      (update :style-and-metadata truncate-string max-chars overflow)

      (vector? (:sections object))
      (update :sections
              (fn [sections]
                (mapv #(update % :section/text truncate-string max-chars overflow)
                      sections))))))

(defn- printable
  [value]
  (cond
    (nil? value) ""
    (string? value) value
    (and (vector? value) (every? #(contains? % :section/text) value))
    (->> value
         (map (fn [section]
                (str "[" (:section/label section) "]\n"
                     (:section/text section))))
         (str/join "\n\n"))
    :else (pr-str value)))

(def template-pattern #"\{\{([^}]+)\}\}")

(defn render-template
  "Render the intentionally small {{key}} template language."
  [template values]
  (str/replace
   template
   template-pattern
   (fn [[_ raw-key]]
     (let [key (keyword raw-key)
           value (or (get values key)
                     (get values (keyword (str/replace raw-key "/" "-"))))]
       (printable value)))))

(defn- render-object
  [template object]
  (render-template
   template
   (merge object
          {:sections (:sections object)
           :features (:features object)})))

(defn- apply-token-budget
  [text {:keys [max-tokens overflow]}]
  (let [max-chars (* 4 max-tokens)]
    (if (<= (count text) max-chars)
      text
      (case overflow
        :fail (throw (ex-info "Rendered context exceeds token budget."
                              {:estimated-tokens (quot (count text) 4)
                               :max-tokens max-tokens}))
        (subs text 0 max-chars)))))

(defn- feature-ledger-paths
  [program]
  (->> (:extractors program)
       vals
       (map #(get-in % [:extractor/emits :event/ledger]))
       distinct
       vec))

(defn- load-feature-index
  [runtime program]
  (reduce
   (fn [index path]
     (reduce
      (fn [m event]
        (if (= :feature/extracted (:event/type event))
          (assoc m [(:object/id event) (:feature/id event) (:cache/key event)] event)
          m))
      index
      ((runtime-fn runtime :read-ledger) path)))
   {}
   (feature-ledger-paths program)))

(defn extractor-cache-key
  [object extractor plan]
  (let [parts (get-in extractor [:extractor/cache :cache/key])
        model (:model plan)
        prompt (:prompt plan)
        context (:context plan)
        values
        {:object-content-sha256
         (or (:object/content-sha256 object)
             (:body-sha256 object)
             (sha256 (pr-str object)))
         :extractor-version (:extractor/version extractor)
         :model-digest (or (get-in model [:model/options :digest])
                           (:model/name model))
         :prompt-version (:prompt/version prompt)
         :context-version (when context (sha256 (pr-str context)))}]
    (sha256 (pr-str (into (sorted-map)
                          (map (fn [part] [part (get values part)]))
                          parts)))))

(defn- normalize-feature-results
  [value]
  (cond
    (and (map? value) (:feature/id value)) [value]
    (vector? value) value
    (and (map? value) (vector? (:features value))) (:features value)
    :else
    (throw (ex-info "Extractor output does not contain feature observations."
                    {:output/value value}))))

(defn- strip-code-fence
  [text]
  (let [trimmed (str/trim text)]
    (if-let [[_ body] (re-matches #"(?s)^```(?:edn|json)?\s*(.*?)\s*```$" trimmed)]
      body
      trimmed)))

(defn parse-model-output
  [format text]
  (let [body (strip-code-fence text)]
    (case format
      :edn (edn/read-string body)
      :json (json/read-str body :key-fn keyword)
      (throw (ex-info "Unknown output format." {:output/format format})))))

(defn json-safe
  "Render every keyword as its FULL name so namespaces survive JSON encoding.

  `clojure.data.json` writes `:object/id` as `\"id\"`, silently dropping the
  namespace. A schema encoded that way asks the model for the wrong keys, so
  keywords are stringified explicitly before the payload is serialized."
  [form]
  (walk/postwalk #(if (keyword? %) (subs (str %) 1) %) form))

(defn ->json-schema
  "Translate a compiled Malli output contract into the JSON Schema that Ollama's
  structured-output and tool-calling modes consume."
  [schema]
  (json-safe (json-schema/transform schema)))

(defn decode-json-value
  "Coerce a JSON-decoded value into the EDN types the Malli contract declares.

  This is what turns `\"destabilized\"` into `:destabilized`, the string
  `\"calliope/production-style-v1\"` into a namespaced keyword, and an
  integer `1` into `1.0` where the contract wants a double."
  [schema value]
  (m/decode schema value (mt/json-transformer)))

(defn tool-name
  "Derive a stable function name for tool-calling mode from an output id."
  [output-id]
  (-> (str "emit_" (name output-id))
      (str/replace #"[^A-Za-z0-9_]+" "_")))

(defn- http-post-json
  [endpoint payload timeout-ms]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder (URI/create endpoint))
                    (.timeout (java.time.Duration/ofMillis timeout-ms))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString
                            (json/write-str payload)))
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)]
    (when-not (<= 200 status 299)
      (throw (ex-info "Model endpoint returned a non-success status."
                      {:http/status status
                       :http/body (.body response)
                       :http/endpoint endpoint})))
    (json/read-str (.body response) :key-fn keyword)))

(defn- tool-call-arguments
  "Extract the single expected tool call, or explain why none arrived."
  [calls message provider]
  (if-let [call (first calls)]
    (get-in call [:function :arguments])
    (throw (ex-info "Model returned no tool call."
                    {:model/provider provider
                     :message/content (:content message)
                     :message/thinking (:thinking message)}))))

(defn invoke-model-http
  "Call one model endpoint.

  Returns the assistant's text, except in tool-calling mode where it returns
  the already-decoded argument map. Callers must handle both."
  [{:keys [model messages runtime-options output-schema tools?]}]
  (let [provider (:model/provider model)
        timeout-ms (or (:timeout-ms runtime-options) 120000)
        temperature (or (:temperature runtime-options)
                        (get-in model [:model/options :temperature])
                        0.0)
        json-schema (when output-schema (->json-schema output-schema))
        tool-fn-name (:tool-name runtime-options)]
    (case provider
      :ollama
      (let [base (str/replace (:model/endpoint model) #"/$" "")
            response
            (http-post-json
             (str base "/api/chat")
             (cond-> {:model (:model/name model)
                      :messages messages
                      :stream false
                      :options (merge (:model/options model)
                                      {:temperature temperature})}
               (and json-schema (not tools?))
               (assoc :format json-schema)

               (and json-schema tools?)
               (assoc :tools [{:type "function"
                               :function {:name tool-fn-name
                                          :description
                                          "Emit the extracted result."
                                          :parameters json-schema}}]))
             timeout-ms)]
        (if tools?
          (tool-call-arguments (get-in response [:message :tool_calls])
                               (:message response)
                               provider)
          (get-in response [:message :content])))

      :llama-cpp
      (let [response
            (http-post-json
             (:model/endpoint model)
             (cond-> {:model (:model/name model)
                      :messages messages
                      :temperature temperature
                      :stream false}
               (and json-schema (not tools?))
               (assoc :response_format
                      {:type "json_schema"
                       :json_schema {:name "output" :schema json-schema}})

               (and json-schema tools?)
               (assoc :tools [{:type "function"
                               :function {:name tool-fn-name
                                          :description
                                          "Emit the extracted result."
                                          :parameters json-schema}}]))
             timeout-ms)]
        (if tools?
          ;; OpenAI-compatible servers serialize arguments as a JSON string.
          (let [calls (get-in response [:choices 0 :message :tool_calls])
                raw (get-in (first calls) [:function :arguments])]
            (if (nil? raw)
              (tool-call-arguments nil
                                   (get-in response [:choices 0 :message])
                                   provider)
              (cond-> raw
                (string? raw) (json/read-str :key-fn keyword))))
          (get-in response [:choices 0 :message :content])))

      (throw (ex-info "Unknown model provider."
                      {:model/provider provider})))))

(defn- invoke-model
  [runtime request]
  (if-let [invoke! (:invoke-model! runtime)]
    (invoke! request)
    (invoke-model-http request)))

(defn contract-instruction
  "The closing instruction that makes a prompt agree with its output contract.

  The contract decides how the model is constrained, so it must also decide
  what the model is told. Telling a model to \"return one EDN value\" while
  handing it a function to call yields neither."
  [contract output]
  (case contract
    :inline-schema
    (str "\n\nOutput Malli schema:\n" (pr-str (:output/schema output)))

    ;; Ollama's own guidance is to still ask for JSON in the prompt; the
    ;; grammar constrains the shape, the instruction sets the intent.
    :provider-native
    "\n\nReturn the result as JSON matching the required schema. Return no prose."

    :tool-call
    (str "\n\nCall the " (tool-name (:output/id output))
         " function with the extracted values. Do not reply with prose.")

    nil))

(defn- prompt-messages
  [prompt variables output]
  (let [messages
        (mapv
         (fn [message]
           {:role (name (:message/role message))
            :content (render-template (:message/template message) variables)})
         (:prompt/messages prompt))]
    (if-let [instruction (contract-instruction (:prompt/output-contract prompt)
                                               output)]
      (update-in messages [(dec (count messages)) :content] str instruction)
      messages)))

(defn- validate-output
  [schema value]
  (when-not (m/validate schema value)
    (m/explain schema value)))

(defn- model-candidates
  [plan]
  (into [(:model plan)] (:fallback-models plan)))

(defn structured-contract?
  "Does this output contract put a machine-checked schema on the wire?"
  [contract]
  (contains? #{:provider-native :tool-call} contract))

(defn call-model-and-validate
  [runtime plan messages]
  (let [output (:output plan)
        schema (:output-schema plan)
        contract (get-in plan [:prompt :prompt/output-contract])
        structured? (structured-contract? contract)
        tools? (= :tool-call contract)
        ;; A schema-constrained endpoint always answers in JSON, whatever the
        ;; program declared for the free-text path.
        format (if structured? :json (:output/format output))
        candidates (model-candidates plan)
        runtime-options
        (cond-> (or (get-in plan [:classifier :classifier/runtime])
                    (get-in plan [:extractor :extractor/runtime]))
          tools? (assoc :tool-name (tool-name (:output/id output))))
        max-attempts (or (:max-attempts runtime-options) 1)
        max-repairs (case (:output/on-invalid output)
                      :reject 0
                      :repair-once 1
                      :repair-until-limit
                      (or (:output/max-repair-attempts output) 1))
        max-total (* (max 1 (count candidates))
                     max-attempts
                     (inc max-repairs))]
    (loop [attempt 0
           repair 0
           current-messages messages
           failures []]
      (when (>= attempt max-total)
        (throw (ex-info "All model attempts failed."
                        {:attempts attempt :failures failures})))
      (let [model (nth candidates (mod attempt (count candidates)))
            invocation
            (try
              {:text (invoke-model runtime
                                   {:model model
                                    :messages current-messages
                                    :runtime-options runtime-options
                                    :output-schema (when structured? schema)
                                    :tools? tools?})}
              (catch Exception error
                {:error error}))]
        (if-let [error (:error invocation)]
          (recur (inc attempt)
                 repair
                 current-messages
                 (conj failures {:model (:model/id model)
                                 ;; ConnectException carries a nil message, so
                                 ;; fall back to the class name to keep the
                                 ;; failure list diagnosable.
                                 :error (or (.getMessage ^Exception error)
                                            (.getName (class error)))}))
          (let [text (:text invocation)
                parsed
                (try
                  ;; Tool calling yields an already-decoded argument map;
                  ;; every other path yields text that still needs parsing.
                  (let [raw (if (string? text)
                              (parse-model-output format text)
                              text)]
                    {:value (if structured?
                              (decode-json-value schema raw)
                              raw)})
                  (catch Exception error
                    {:error error}))]
            (if-let [error (:error parsed)]
              ;; A malformed reply is repairable: tell the model what broke.
              ;; Resending the identical prompt just reproduces the failure.
              (if (< repair max-repairs)
                (recur (inc attempt)
                       (inc repair)
                       (conj current-messages
                             {:role "assistant" :content (str text)}
                             {:role "user"
                              :content
                              (str "The previous response could not be parsed as "
                                   (name format) ": "
                                   (.getMessage ^Exception error)
                                   "\nReturn only a single well-formed "
                                   (name format) " value.")})
                       (conj failures {:model (:model/id model)
                                       :error (.getMessage ^Exception error)
                                       :raw text}))
                (recur (inc attempt)
                       repair
                       current-messages
                       (conj failures {:model (:model/id model)
                                       :error (.getMessage ^Exception error)
                                       :raw text})))
              (let [value (:value parsed)
                    explanation (validate-output schema value)]
                (if-not explanation
                  {:value value
                   :model model
                   :raw text
                   :attempts (inc attempt)
                   :repairs repair}
                  (if (< repair max-repairs)
                    (recur (inc attempt)
                           (inc repair)
                           (conj current-messages
                                 {:role "assistant" :content text}
                                 {:role "user"
                                  :content
                                  (str "The previous response failed validation. Return only a corrected "
                                       (name format) " value.\n\nValidation explanation:\n"
                                       (pr-str explanation) "\n\nSchema:\n"
                                       (pr-str (:output/schema output)))})
                           (conj failures {:model (:model/id model)
                                           :validation explanation}))
                    (recur (inc attempt)
                           repair
                           current-messages
                           (conj failures {:model (:model/id model)
                                           :validation explanation
                                           :raw text}))))))))))))

(declare run-extractor!)

(defn- ensure-feature!
  [state object feature-id]
  (let [{:keys [program producer-index cache dry-run?]} state
        producer-id (first (get producer-index feature-id))]
    (when-not producer-id
      (throw (ex-info "No extractor produces required feature."
                      {:feature/id feature-id})))
    (let [plan (dsl/compile-extractor-plan program producer-id)
          extractor (:extractor plan)
          key (extractor-cache-key object extractor plan)
          existing (get @cache [(:object/id object) feature-id key])]
      (cond
        existing
        {:feature/id feature-id
         :feature/value (:feature/value existing)
         :feature/status (:event/status existing)
         :cache/disposition :hit
         :cache/key key}

        dry-run?
        {:feature/id feature-id
         :feature/status :missing
         :cache/disposition :dry-run-miss
         :cache/key key}

        :else
        (let [events (run-extractor! state producer-id object)
              event
              (or (first (filter #(= feature-id (:feature/id %)) events))
                  (throw
                   (ex-info "Extractor ran but did not produce the requested feature."
                            {:feature/id feature-id
                             :extractor/id producer-id
                             :object/id (:object/id object)
                             :produced-feature-ids (mapv :feature/id events)})))]
          {:feature/id feature-id
           :feature/value (:feature/value event)
           :feature/status (:event/status event)
           :cache/disposition :extracted
           :cache/key (:cache/key event)})))))

(defn execute-context
  "Execute one context dataflow graph. Returns all bindings and the final text."
  [state context selected]
  (let [runtime (:runtime state)
        bindings
        (reduce
         (fn [bindings step]
           (let [input (get bindings (:step/input step))
                 output
                 (case (:step/op step)
                   :hydrate
                   (mapv #((resolver runtime (:step/resolver step)) runtime %)
                         input)

                   :segment
                   (case (:step/strategy step)
                     :explicit-song-sections
                     (mapv #(assoc % :sections (explicit-sections (:lyrics %))) input)
                     (throw (ex-info "Segmentation strategy is not implemented."
                                     {:step/strategy (:step/strategy step)})))

                   :limit
                   (->> input
                        (keep #(limit-object %
                                             (:step/max-chars-per-object step)
                                             (:step/overflow step)))
                        vec)

                   :attach-features
                   (mapv
                    (fn [object]
                      (assoc object
                             :features
                             (mapv #(ensure-feature! state object %)
                                   (:step/features step))))
                    input)

                   :render
                   (->> input
                        (map #(render-object (:step/template step) %))
                        (str/join "\n\n"))

                   (throw (ex-info "Context operation is not implemented."
                                   {:step/op (:step/op step)})))]
             (assoc bindings (:step/as step) output)))
         {:selected selected}
         (:context/steps context))
        output-key (:context/output-key context)
        rendered (get bindings output-key)]
    (assoc bindings output-key
           (if (string? rendered)
             (apply-token-budget rendered (:context/token-budget context))
             rendered))))

(defn- producer-index
  [program]
  (reduce-kv
   (fn [index extractor-id extractor]
     (reduce #(update %1 %2 (fnil conj []) extractor-id)
             index
             (:extractor/produces extractor)))
   {}
   (:extractors program)))

(defn- run-state
  [runtime program options]
  {:runtime runtime
   :program program
   :producer-index (producer-index program)
   :cache (atom (load-feature-index runtime program))
   :run-seed (:run-seed options)
   :dry-run? (:dry-run? options)})

(defn- append-feature-events!
  [state extractor object plan model-result feature-results]
  (let [runtime (:runtime state)
        program (:program state)
        key (extractor-cache-key object extractor plan)
        ledger (get-in extractor [:extractor/emits :event/ledger])]
    (mapv
     (fn [{:keys [feature/id feature/value]}]
       (let [event
             {:event/id ((runtime-fn runtime :uuid))
              :event/type (get-in extractor [:extractor/emits :event/type])
              :event/status (get-in extractor [:extractor/emits :event/status])
              :ts ((runtime-fn runtime :now))
              :program/id (:program/id program)
              :extractor/id (:extractor/id extractor)
              :extractor/version (:extractor/version extractor)
              :object/id (:object/id object)
              :object/type (:object/type object)
              :object/content-sha256
              (or (:object/content-sha256 object) (:body-sha256 object))
              :feature/id id
              :feature/value value
              :cache/key key
              :model/id (get-in model-result [:model :model/id])
              :model/name (get-in model-result [:model :model/name])
              :prompt/version (get-in plan [:prompt :prompt/version])
              :validation/status :accepted}]
         ((runtime-fn runtime :append-event!) ledger event)
         (swap! (:cache state) assoc [(:object/id object) id key] event)
         event))
     feature-results)))

(defn run-extractor!
  "Run one deterministic or LLM extractor for one object and append feature events."
  [state extractor-id object]
  (let [program (:program state)
        runtime (:runtime state)
        plan (dsl/compile-extractor-plan program extractor-id)
        extractor (:extractor plan)
        value
        (case (:extractor/type extractor)
          :deterministic
          ((resolver runtime (:extractor/resolver extractor)) runtime object)

          :llm
          (let [selected [object]
                context-bindings (execute-context state (:context plan) selected)
                variables (assoc context-bindings :run/seed (:run-seed state))
                messages (prompt-messages (:prompt plan) variables (:output plan))]
            (call-model-and-validate runtime plan messages))

          (throw (ex-info "Extractor type is not implemented."
                          {:extractor/type (:extractor/type extractor)})))
        model-result (if (= :llm (:extractor/type extractor)) value nil)
        output-value (if model-result (:value model-result) value)
        explanation (validate-output (:output-schema plan) output-value)]
    (when explanation
      (throw (ex-info "Deterministic extractor output failed validation."
                      {:extractor/id extractor-id
                       :validation explanation
                       :value output-value})))
    (append-feature-events! state extractor object plan model-result
                            (normalize-feature-results output-value))))

(defn prepare-classifier
  "Compile selection, features, context, and prompt messages without invoking the
  classifier model or appending its final event. Feature extraction is skipped
  when :dry-run? is true."
  [runtime program classifier-id options]
  (let [plan (dsl/compile-plan program classifier-id)
        state (run-state runtime program options)
        objects (load-source runtime (:source plan))
        selected (execute-selector objects (:selector plan) options)
        context-bindings (execute-context state (:context plan) selected)
        variables (assoc context-bindings :run/seed (:run-seed options))
        messages (prompt-messages (:prompt plan) variables (:output plan))]
    {:plan plan
     :state state
     :selected selected
     :context context-bindings
     :messages messages}))

(defn run-classifier!
  "Execute one classifier and append its provenance-bearing result event."
  [runtime program classifier-id options]
  (let [{:keys [plan selected messages]} (prepare-classifier
                                          runtime program classifier-id options)]
    (if (:dry-run? options)
      {:dry-run? true
       :classifier/id classifier-id
       :selected selected
       :messages messages}
      (let [model-result (call-model-and-validate runtime plan messages)
            classifier (:classifier plan)
            ledger (get-in classifier [:classifier/emits :event/ledger])
            event
            {:event/id ((runtime-fn runtime :uuid))
             :event/type (get-in classifier [:classifier/emits :event/type])
             :event/status (get-in classifier [:classifier/emits :event/status])
             :ts ((runtime-fn runtime :now))
             :program/id (:program/id program)
             :classifier/id (:classifier/id classifier)
             :classifier/version (:classifier/version classifier)
             :selection/seed (:run-seed options)
             :selection/object-ids (mapv :object/id selected)
             :selection/content-sha256s
             (mapv #(or (:object/content-sha256 %) (:body-sha256 %)) selected)
             :model/id (get-in model-result [:model :model/id])
             :model/name (get-in model-result [:model :model/name])
             :prompt/version (get-in plan [:prompt :prompt/version])
             :validation/status :accepted
             :result (:value model-result)}]
        ((runtime-fn runtime :append-event!) ledger event)
        {:event event
         :result (:value model-result)
         :selected selected
         :model (:model model-result)}))))
