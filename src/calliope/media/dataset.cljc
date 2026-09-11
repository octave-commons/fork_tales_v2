(ns calliope.media.dataset
  "Manifest-addressed external media dataset operations.

  A dataset is any directory containing `MANIFEST.edn` and its media bytes. The
  `CALLIOPE_MEDIA_ROOT` environment variable selects that directory; otherwise
  the repository's `tracks/` directory is used. Media bytes are externally
  synchronized, while the manifest makes their identity and integrity portable."
  (:require [calliope.media.manifest :as manifest]
            #?(:clj [clojure.edn :as edn])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str])
  #?(:clj (:import [java.io BufferedInputStream BufferedReader File FileInputStream InputStreamReader PushbackReader StringReader]
                   [java.math BigInteger]
                   [java.nio.file Files FileVisitOption Path]
                   [java.security MessageDigest]
                   [java.time Instant])))

;; ---------------------------------------------------------------- constants

(def dataset-id manifest/dataset-id)
(def manifest-schema manifest/schema)
(def env-var "CALLIOPE_MEDIA_ROOT")
(def default-dataset-dir "tracks")
(def manifest-name "MANIFEST.edn")
(def content-extensions #{"mp3" "jpeg" "json" "md" "txt"})
(def text-dir "text")
(def text-source-dir "docs/lyrics")
(def text-extensions #{"md" "txt"})
(def ledger-checkable-extensions #{"mp3" "jpeg" "json"})

;; --------------------------------------------------------------- resolution

#?(:clj
   (defn find-repo-root
     "Walk from `start` upward to the directory containing `deps.edn`.
     Returns its canonical string path, or throws when no repository is found."
     [start]
     (loop [dir (let [f (File. (str start))]
                  (if (.isDirectory f) f (.getParentFile f)))]
       (cond
         (nil? dir) (throw (ex-info "No repository root containing deps.edn" {:start (str start)}))
         (.isFile (File. dir "deps.edn")) (.getCanonicalPath dir)
         :else (recur (.getParentFile dir)))))
   :cljs
   (defn find-repo-root [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

(defn resolve-root
  "Resolve a dataset root. A non-blank value in `env` takes precedence over the
  repository default and the returned `:source` preserves that provenance."
  ([repo-root] (resolve-root repo-root nil))
  ([repo-root env]
   (if (str/blank? env)
     {:root (str repo-root "/" default-dataset-dir) :source :default}
     {:root env :source :env})))

(defn manifest-path
  "Return the manifest path for a dataset root."
  [root]
  (str root "/" manifest-name))

#?(:clj
   (defn resolve-file
     "Resolve a POSIX relative path, rejecting traversal and symlinks outside root."
     [root relpath]
     (when-not (manifest/relative-path? relpath)
       (throw (ex-info "Invalid dataset-relative path" {:path relpath})))
     (let [base (.getCanonicalFile (File. (str root)))
           file (.getCanonicalFile (File. base relpath))]
       (when-not (and (not= base file)
                      (.startsWith (.toPath file) (.toPath base)))
         (throw (ex-info "Dataset path escapes root" {:root (str base) :path relpath})))
       file))
   :cljs
   (defn resolve-file [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

;; --------------------------------------------------------------- hashing

#?(:clj
   (defn sha256-of-file
     "Stream `f` through SHA-256 in 64KB chunks and return lowercase hex."
     [f]
     (let [digest (MessageDigest/getInstance "SHA-256")
           buffer (byte-array 65536)]
       (with-open [in (BufferedInputStream. (FileInputStream. (File. (str f))))]
         (loop [read (.read in buffer)]
           (when (pos? read)
             (.update digest buffer 0 read)
             (recur (.read in buffer)))))
       (format "%064x" (BigInteger. 1 (.digest digest)))))
   :cljs
   (defn sha256-of-file [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

;; --------------------------------------------------------------- manifest

#?(:clj
   (defn- relative-path [^File root ^File file]
     (-> (.relativize (.toPath root) (.toPath file)) str (str/replace "\\" "/"))))

(defn- extension [path]
  (some->> (re-find #"(?i)\.([^.]+)$" (str path)) second str/lower-case))

#?(:clj
   (defn- files-under [^File root]
     ;; Files/walk does not follow directory symlinks, including cycles.
     (with-open [paths (Files/walk (.toPath root) (make-array FileVisitOption 0))]
       (mapv #(.toFile ^Path %) (iterator-seq (.iterator paths))))))

#?(:clj
   (defn- media-files [root]
     (let [root-file (.getCanonicalFile (File. (str root)))]
       (->> (files-under root-file)
            (filter #(.isFile ^File %))
            (remove #(= manifest-name (.getName ^File %)))
            (filter #(contains? content-extensions (extension (.getName ^File %))))))))

#?(:clj
   (defn scan-entries
     "Recursively hash media files under `root`, returning path-sorted entries.
     Paths are POSIX dataset-relative paths; every non-media file is ignored."
     [root]
     (let [root-file (.getCanonicalFile (File. (str root)))]
       (->> (media-files root)
            (map (fn [^File file]
                   (let [path (relative-path root-file file)
                         resolved (resolve-file root path)]
                     {:path path
                      :bytes (.length ^File resolved)
                      :sha256 (sha256-of-file resolved)})))
            (sort-by :path)
            vec)))
   :cljs
   (defn scan-entries [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

#?(:clj
   (defn scan-media-paths
     "Path-sorted dataset-relative media paths under `root`, without hashing."
     [root]
     (let [root-file (.getCanonicalFile (File. (str root)))]
       (->> (media-files root)
            (map #(relative-path root-file ^File %))
            (sort-by identity)
            vec))))

#?(:clj
   (defn assemble-text!
     "Copy the canonical songbook projection (docs/lyrics/*.md|*.txt) from
     `repo-root` into `<root>/text/`, overwriting in place and removing stale
     text copies. The repository stays the authority; this copy exists so one dataset folder carries the
     complete corpus. Returns the number of files written."
     [repo-root root]
     (let [source (File. (str repo-root) text-source-dir)]
       (when-not (.isDirectory source)
         (throw (ex-info "Songbook projection missing — run `bb scripts/corpus.clj project` first."
                         {:dir (str source)})))
       (let [dest (resolve-file root text-dir)
             files (or (.listFiles source)
                       (throw (ex-info "Cannot list songbook projection" {:dir (str source)})))
             supported (filterv (fn [^File f]
                                  (and (.isFile f)
                                       (contains? text-extensions (extension (.getName f))))) files)
             names (set (map #(.getName ^File %) supported))]
         (when (= (.getCanonicalFile source) dest)
           (throw (ex-info "Dataset text directory overlaps songbook source" {:dir (str dest)})))
         (.mkdirs dest)
         (doseq [^File f supported]
           (let [target (resolve-file root (str text-dir "/" (.getName f)))]
             (when (.startsWith (.toPath target) (.toPath (.getCanonicalFile source)))
               (throw (ex-info "Dataset text file overlaps songbook source" {:path (str target)})))
             (io/copy f target)))
         (doseq [^File f (files-under dest)
                 :when (and (.isFile f)
                            (contains? text-extensions (extension (.getName f)))
                            (not (contains? names (relative-path dest f))))]
           ;; Validate containment before deleting any regenerable copy.
           (resolve-file root (str text-dir "/" (relative-path dest f)))
           (Files/delete (.toPath f)))
         (count supported))))
   :cljs
   (defn assemble-text! [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

#?(:clj
   (defn- parse-line [line-number line]
     (try
       (with-open [reader (PushbackReader. (StringReader. line))]
         (let [eof (Object.)
               value (edn/read {:eof eof} reader)]
           (when (or (identical? value eof)
                     (not (identical? eof (edn/read {:eof eof} reader))))
             (throw (ex-info "Expected exactly one EDN form" {})))
           value))
       (catch Exception cause
         (throw (ex-info (str "Malformed manifest line " line-number)
                         {:line line-number} cause))))))

#?(:clj
   (defn read-manifest
     "Read and validate every manifest form, identity, path, count and byte total.
     Invalid data names its offending line; duplicate paths are rejected."
     [root]
     (let [path (manifest-path root)]
       (with-open [reader (BufferedReader. (InputStreamReader. (FileInputStream. (File. path))))]
         (let [lines (doall (line-seq reader))]
           (when-not (seq lines)
             (throw (ex-info "Malformed manifest line 1" {:line 1 :path path})))
           (let [envelope (parse-line 1 (first lines))
                 entries (mapv (fn [line-number line]
                                 (parse-line line-number line))
                               (range 2 (+ 2 (count (rest lines))))
                               (rest lines))]
             (when-not (manifest/envelope? envelope)
               (throw (ex-info "Malformed manifest line 1: invalid envelope"
                               {:line 1 :path path})))
             (doseq [[index entry] (map-indexed vector entries)]
               (when-not (manifest/entry? entry)
                 (throw (ex-info (str "Malformed manifest line " (+ 2 index) ": invalid entry")
                                 {:line (+ 2 index) :path path}))))
             (when-not (= (:entries envelope) (count entries))
               (throw (ex-info "Malformed manifest line 1: entry count mismatch"
                               {:line 1 :path path :expected (:entries envelope)
                                :actual (count entries)})))
             (when-not (= (:bytes-total envelope) (reduce +' 0 (map :bytes entries)))
               (throw (ex-info "Malformed manifest line 1: byte total mismatch"
                               {:line 1 :path path})))
             (when-not (= (count entries) (count (set (map :path entries))))
               (throw (ex-info "Malformed manifest: duplicate entry paths" {:path path})))
             {:dataset/id (:dataset/id envelope)
              :schema (:schema envelope)
              :generated (:generated envelope)
              :bytes-total (:bytes-total envelope)
              :entries entries})))))
   :cljs
   (defn read-manifest [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

#?(:clj
   (defn write-manifest!
     "Scan `root` and write its deterministic line-oriented manifest."
     [root {:keys [generated]}]
     (let [entries (scan-entries root)
           bytes-total (reduce + 0 (map :bytes entries))
           path (manifest-path root)
           envelope {:dataset/id dataset-id
                     :schema manifest-schema
                     :entries (count entries)
                     :bytes-total bytes-total
                     :generated generated}]
       (when-not (and (manifest/envelope? envelope) (every? manifest/entry? entries))
         (throw (ex-info "Cannot write invalid or empty media manifest" {:path path})))
       (spit path (str (str/join "\n" (map pr-str (cons envelope entries))) "\n"))
       {:entries (count entries) :bytes-total bytes-total :path path}))
   :cljs
   (defn write-manifest! [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

(defn generate-manifest!
  "Write a manifest stamped with the current ISO-8601 instant."
  [root]
  #?(:clj (write-manifest! root {:generated (str (Instant/now))})
     :cljs (throw (ex-info "Media datasets require a JVM filesystem" {:root root}))))

;; --------------------------------------------------------------- verification

(defn entry-for
  "Return the manifest entry for dataset-relative `relpath`, if present."
  [manifest relpath]
  (some #(when (= relpath (:path %)) %) (:entries manifest)))

#?(:clj
   (defn verify
     "Verify manifest entries against disk. Extra media files are reported but
     do not change `:ok`; missing, size, and optional hash failures do."
     [root {:keys [hash?]}]
     (let [manifest (read-manifest root)
           checked (:entries manifest)
           report (reduce (fn [acc {:keys [path bytes sha256]}]
                            (let [file (resolve-file root path)
                                  actual-hash (when (and hash? (.isFile ^File file))
                                                (sha256-of-file file))]
                              (cond
                                (not (.isFile ^File file))
                                (update acc :missing conj path)

                                (not= bytes (.length ^File file))
                                (update acc :size-mismatch conj {:path path :expected bytes :actual (.length ^File file)})

                                (and hash? (not= sha256 actual-hash))
                                (update acc :hash-mismatch conj {:path path :expected sha256 :actual actual-hash})

                                :else acc)))
                          {:missing [] :size-mismatch [] :hash-mismatch []}
                          checked)
          listed (set (map :path checked))
          extras (->> (scan-media-paths root) (remove listed) vec)]
       (assoc report
              :ok (every? empty? (vals report))
              :checked (count checked)
              :extras extras)))
   :cljs
   (defn verify [& _]
     (throw (ex-info "Media datasets require a JVM filesystem" {}))))

(defn normalize-dest
  "Convert a historical repo-relative track destination to a dataset-relative path."
  [dest]
  (str/replace-first dest #"^tracks/" ""))

(defn verify-against-ledger
  "Compare media and metadata manifest entries with supplied
  :track/discovered events, keyed by dataset-relative path with one
  historical leading `tracks/` stripped. Songbook text has no track events
  and is excluded here; JSON metadata events are included."
  [root events]
  (let [manifest (read-manifest root)
        checkable? (fn [entry]
                     (contains? ledger-checkable-extensions (extension (:path entry))))
        entries (filter checkable? (:entries manifest))
        manifest-by-path (into {} (map (juxt :path identity)) entries)
        events-by-path (into {}
                             (map (fn [event] [(normalize-dest (:dest event)) event]))
                             (filter #(and (= :track/discovered (:event/type %))
                                           (contains? #{:mp3 :jpeg :json} (:asset %)))
                                     events))
        untracked (->> entries (map :path) (remove events-by-path) vec)
        missing (->> (keys events-by-path) (remove manifest-by-path) sort vec)
        drift (->> events-by-path
                   (keep (fn [[path event]]
                           (when-let [entry (manifest-by-path path)]
                             (when (not= (:bytes event) (:bytes entry))
                               {:path path :expected (:bytes event) :actual (:bytes entry)}))))
                   (sort-by :path)
                   vec)
        hash-drift (->> events-by-path
                        (keep (fn [[path event]]
                                (when-let [entry (manifest-by-path path)]
                                  (when (and (contains? event :sha256)
                                             (not= (:sha256 event) (:sha256 entry)))
                                    {:path path :expected (:sha256 event) :actual (:sha256 entry)}))))
                        (sort-by :path)
                        vec)]
    {:untracked-in-ledger untracked
     :missing-from-manifest missing
     :bytes-drift drift
     :hash-drift hash-drift}))
