#!/usr/bin/env bb
;; Calliope external media dataset operations.
;;
;; A dataset directory contains MANIFEST.edn plus MP3 and JPEG bytes. Its root
;; comes from CALLIOPE_MEDIA_ROOT or defaults to tracks/ beneath this repository.
;; Media bytes synchronize through rclone, never through git.
;;
;; Usage:
;;   bb scripts/media.clj where     print the selected dataset and manifest status
;;   bb scripts/media.clj assemble  copy the docs/lyrics songbook into <root>/text/
;;   bb scripts/media.clj manifest  generate a manifest from local content bytes
;;   bb scripts/media.clj verify    verify local bytes, optionally against the ledger
;;   bb scripts/media.clj sync      synchronize manifest-addressed files to rclone
;;   bb scripts/media.clj check     check local files against the rclone remote

(require '[babashka.fs :as fs]
          '[babashka.process :as p]
          '[calliope.media.dataset :as media]
          '[clojure.edn :as edn]
          '[clojure.pprint :as pprint]
          '[clojure.string :as str])

(def repo-root (media/find-repo-root *file*))

(defn usage []
  (println "usage: bb scripts/media.clj [where|assemble|manifest|verify|sync|check]")
  (println "  manifest [--allow-removals]  omit old paths only after deliberate removal"))

(defn resolved-root []
  (media/resolve-root repo-root (System/getenv media/env-var)))

(defn remote-flag [args]
  (when (seq args)
    (let [[flag value] args]
      (when-not (and (= 2 (count args)) (= "--remote" flag)
                     (not (str/blank? value)) (not (str/starts-with? value "-")))
        (println "ERROR: expected --remote <value>, e.g. --remote gdrive:calliope-media")
        (System/exit 1))
      value)))

(defn validate-args! [command args]
  (case command
    ("sync" "check") (remote-flag args)
    (let [allowed (case command
                    "verify" #{"--no-hash" "--ledger"}
                    "manifest" #{"--allow-removals"}
                    #{})]
      (when (seq (remove allowed args))
        (println "ERROR: unexpected arguments for" command)
        (usage)
        (System/exit 1)))))

(defn remote [args]
  (let [value (or (remote-flag args)
                  (System/getenv "CALLIOPE_MEDIA_REMOTE")
                  "gdrive:calliope-media")]
    (when (or (str/blank? value) (str/starts-with? value "-"))
      (println "ERROR: media remote must be a nonempty destination, not an option")
      (System/exit 1))
    value))

(defn require-manifest! [root]
  (when-not (fs/exists? (media/manifest-path root))
    (println "ERROR: MANIFEST.edn is missing — run `bb scripts/media.clj manifest` first.")
    (System/exit 1)))

(defn files-from!
  ([root] (files-from! root true))
  ([root include-manifest?]
   (let [manifest (media/read-manifest root)
        target (str (fs/path repo-root "target"))
        path (str (fs/path target (if include-manifest? "media-files-from.txt" "media-content-files-from.txt")))]
    (fs/create-dirs target)
    (spit path (str (str/join "\n" (cond-> (mapv :path (:entries manifest))
                                    include-manifest? (conj media/manifest-name))) "\n"))
    path)))

(defn run-rclone! [args]
  (when-not (fs/which "rclone")
    (println "ERROR: rclone is not installed or is not on PATH.")
    (System/exit 1))
  (let [result @(p/process args {:out :inherit :err :inherit})]
    (when-not (zero? (:exit result))
      (System/exit (:exit result)))))

(defn where! []
  (let [{:keys [root source]} (resolved-root)
        path (media/manifest-path root)
        exists? (fs/exists? path)]
    (println "Root:" root)
    (println "Source:" source)
    (println "Manifest:" (if exists? "present" "absent"))
    (when exists?
      (let [manifest (media/read-manifest root)]
        (println "Entries:" (count (:entries manifest)))
        (println "Bytes total:" (reduce + 0 (map :bytes (:entries manifest))))
        (println "Generated:" (:generated manifest))))))

(defn assemble! []
  (let [{:keys [root]} (resolved-root)
        n (media/assemble-text! repo-root root)]
    (println "Assembled" n "songbook files into" (str (fs/path root media/text-dir)))
    (println "Run `bb scripts/media.clj manifest` next; use --allow-removals only for deliberate removals.")))

(defn manifest! [args]
  (when (seq (remove #{"--allow-removals"} args))
    (usage)
    (System/exit 1))
  (let [{:keys [root]} (resolved-root)
        {:keys [entries bytes-total path]} (media/generate-manifest! root {:allow-removals? (boolean (some #{"--allow-removals"} args))})]
    (println "Entries:" entries)
    (println "Bytes total:" bytes-total)
    (println "Manifest:" path)))

(defn verify! [args]
  (let [{:keys [root]} (resolved-root)
        hash? (not (some #{"--no-hash"} args))
        ledger? (some #{"--ledger"} args)]
    (require-manifest! root)
    (let [report (media/verify root {:hash? hash?})
          ledger-report (when ledger?
                          (media/verify-against-ledger
                           root
                           (with-open [reader (java.io.PushbackReader.
                                              (java.io.FileReader.
                                               (str (fs/path repo-root "ledgers" "ingest.edn"))))]
                             (let [eof (Object.)]
                               (loop [events []]
                                 (let [event (edn/read {:eof eof} reader)]
                                   (cond
                                     (identical? eof event) events
                                     (and (map? event) (keyword? (:event/type event)))
                                     (recur (conj events event))
                                     :else (throw (ex-info "Invalid ledger event form"
                                                           {:form-index (inc (count events))})))))))))
          ledger-failure? (and ledger-report
                               (or (seq (:untracked-in-ledger ledger-report))
                                   (seq (:missing-from-manifest ledger-report))
                                   (seq (:bytes-drift ledger-report))
                                   (seq (:hash-drift ledger-report))))]
      (pprint/pprint report)
      (when ledger-report
        (pprint/pprint ledger-report)
        (when (seq (:untracked-in-ledger ledger-report))
          (println "ERROR: manifest entries lack historical ledger events.")))
      (when (or (not (:ok report)) ledger-failure?)
        (System/exit 1)))))

(defn sync! [args]
  (let [{:keys [root]} (resolved-root)
        destination (remote args)]
    (require-manifest! root)
    (let [report (media/verify root {:hash? true})]
      (when-not (:ok report)
        (pprint/pprint report)
        (println "ERROR: local dataset verification failed; remote was not synchronized.")
        (System/exit 1)))
    (run-rclone! ["rclone" "sync" root destination "--files-from" (files-from! root false)
                  "--transfers" "4" "--checkers" "8" "-v"])
    (run-rclone! ["rclone" "copyto" (media/manifest-path root)
                  (str destination (when-not (or (str/ends-with? destination "/")
                                                  (str/ends-with? destination ":")) "/")
                       media/manifest-name)
                  "--ignore-times"])
    (println "Sync complete. Run `bb scripts/media.clj check` to verify the remote.")))

(defn check! [args]
  (let [{:keys [root]} (resolved-root)]
    (require-manifest! root)
    (run-rclone! ["rclone" "check" root (remote args) "--files-from" (files-from! root) "--one-way"])))

(let [command (first *command-line-args*)
      args (rest *command-line-args*)]
  (validate-args! command args)
  (case command
    "where" (where!)
    "assemble" (assemble!)
    "manifest" (manifest! args)
    "verify" (verify! args)
    "sync" (sync! args)
    "check" (check! args)
    ("help" "--help" "-h") (usage)
    (do (usage) (System/exit 1))))
