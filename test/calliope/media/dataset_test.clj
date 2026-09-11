(ns calliope.media.dataset-test
  (:require [calliope.media.dataset :as dataset]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io File]
           [java.nio.file Files]))

(defn temp-dir []
  (str (Files/createTempDirectory "calliope-media-test-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree! [root]
  (doseq [file (reverse (file-seq (File. root)))]
    (Files/deleteIfExists (.toPath file))))

(defn write-bytes! [root path bytes]
  (let [file (File. root path)]
    (.mkdirs (.getParentFile file))
    (Files/write (.toPath file) bytes (make-array java.nio.file.OpenOption 0))
    file))

(defn dataset! [root]
  (write-bytes! root "absence/2cf24dba.mp3" (.getBytes "hello" "UTF-8"))
  (write-bytes! root "absence/486ea462.jpeg" (.getBytes "world" "UTF-8"))
  (write-bytes! root "absence/ea3bd73e.json" (.getBytes "meta" "UTF-8"))
  (dataset/write-manifest! root {:generated "2026-08-26T00:00:00Z"}))

(defmacro with-dataset [[root] & body]
  `(let [~root (temp-dir)]
     (try
       (dataset! ~root)
       ~@body
       (finally (delete-tree! ~root)))))

(deftest manifest-round-trip-and-intact-verification
  (with-dataset [root]
    (let [manifest (dataset/read-manifest root)]
      (is (= dataset/dataset-id (:dataset/id manifest)))
      (is (= dataset/manifest-schema (:schema manifest)))
      (is (= "2026-08-26T00:00:00Z" (:generated manifest)))
      (is (= 3 (count (:entries manifest))))
      (is (= ["absence/2cf24dba.mp3" "absence/486ea462.jpeg" "absence/ea3bd73e.json"]
             (mapv :path (:entries manifest))))
      (is (= {:ok true :checked 3 :missing [] :size-mismatch [] :hash-mismatch [] :extras []}
             (dataset/verify root {:hash? true}))))))

(deftest verification-detects-missing-size-hash-and-extra-files
  (with-dataset [root]
    (Files/delete (.toPath (dataset/resolve-file root "absence/2cf24dba.mp3")))
    (is (= ["absence/2cf24dba.mp3"] (:missing (dataset/verify root {:hash? true}))))
    (write-bytes! root "absence/2cf24dba.mp3" (.getBytes "x" "UTF-8"))
    (is (= [{:path "absence/2cf24dba.mp3" :expected 5 :actual 1}]
           (:size-mismatch (dataset/verify root {:hash? true}))))
    (write-bytes! root "absence/2cf24dba.mp3" (.getBytes "hullo" "UTF-8"))
    (is (empty? (:hash-mismatch (dataset/verify root {:hash? false}))))
    (is (= ["absence/2cf24dba.mp3"] (mapv :path (:hash-mismatch (dataset/verify root {:hash? true})))))
    (write-bytes! root "absence/extra.mp3" (.getBytes "extra" "UTF-8"))
    (let [report (dataset/verify root {:hash? false})]
      (is (= ["absence/extra.mp3"] (:extras report)))
      (is (:ok report) "extras alone must not fail verification"))))

(deftest paths-and-roots-normalize-as-specified
  (is (= "absence/2cf24dba.mp3" (dataset/normalize-dest "tracks/absence/2cf24dba.mp3")))
  (is (= "absence/2cf24dba.mp3" (dataset/normalize-dest "absence/2cf24dba.mp3")))
  (is (= {:root "/tmp/media" :source :env} (dataset/resolve-root "/repo" "/tmp/media")))
  (is (= {:root "/repo/tracks" :source :default} (dataset/resolve-root "/repo" "   "))))

(deftest metadata-mirror-checks-copied-bytes-and-replaces-hard-links
  (doseq [scenario [:corrupt :hard-link]]
    (with-dataset [root]
      (let [repo (temp-dir)
            tracked (str repo "/tracks")
            target (File. tracked "absence/ea3bd73e.json")
            victim (File. repo "victim")
            manifest (File. tracked dataset/manifest-name)]
        (try
          (.mkdirs (.getParentFile target))
          (spit manifest "previous manifest")
          (spit victim "previous metadata")
          (Files/createLink (.toPath target) (.toPath victim))
          (when (= :corrupt scenario)
            (spit (File. root "absence/ea3bd73e.json") "evil"))
          (if (= :corrupt scenario)
            (do
              (is (thrown? clojure.lang.ExceptionInfo (dataset/mirror-metadata! repo root)))
              (is (= "previous metadata" (slurp target)))
              (is (= "previous manifest" (slurp manifest))))
            (do
              (dataset/mirror-metadata! repo root)
              (is (= "meta" (slurp target)))
              (is (= (dataset/read-manifest root) (dataset/read-manifest tracked)))))
          (is (= "previous metadata" (slurp victim)))
          (finally (delete-tree! repo)))))))

(deftest ledger-verification-checks-media-and-metadata-events
  (with-dataset [root]
    (let [report (dataset/verify-against-ledger
                  root
                  [{:event/type :track/discovered :asset :mp3 :dest "tracks/absence/2cf24dba.mp3" :bytes 5}
                   {:event/type :track/discovered :asset :jpeg :dest "absence/486ea462.jpeg" :bytes 9}
                   {:event/type :track/discovered :asset :json :dest "absence/ea3bd73e.json" :bytes 4}
                   {:event/type :track/discovered :asset :mp3 :dest "absent/three.mp3" :bytes 1}])]
      (is (= [] (:untracked-in-ledger report)))
      (is (= ["absent/three.mp3"] (:missing-from-manifest report)))
      (is (= [{:path "absence/486ea462.jpeg" :expected 9 :actual 5}]
             (:bytes-drift report))))))

(deftest assembly-copies-songbook-text-into-the-dataset
  (let [repo (temp-dir)
        root (temp-dir)]
    (try
      (let [lyrics (File. repo "docs/lyrics")]
        (.mkdirs lyrics)
        (spit (File. lyrics "a.md") "# A")
        (spit (File. lyrics "b.txt") "B")
        (spit (File. lyrics "index.edn") "{}")
        (is (= 2 (dataset/assemble-text! repo root)))
        (is (= "# A" (slurp (dataset/resolve-file root "text/a.md"))))
        (is (= "B" (slurp (dataset/resolve-file root "text/b.txt"))))
        (is (not (.exists (dataset/resolve-file root "text/index.edn"))))
        (dataset/write-manifest! root {:generated "t"})
        (is (= #{"text/a.md" "text/b.txt"}
               (set (filter #(re-find #"^text/" %)
                            (map :path (:entries (dataset/read-manifest root))))))))
      (finally
        (delete-tree! repo)
        (delete-tree! root)))))

(deftest manifest-read-fails-loudly
  (let [root (temp-dir)
        path (dataset/manifest-path root)]
    (try
      (spit path "not-edn\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 1" (dataset/read-manifest root)))
      (spit path "{:schema :wrong :entries 0}\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 1" (dataset/read-manifest root)))
      (spit path "{:dataset/id \"calliope-media\" :schema :calliope.media/manifest-v1 :entries 2 :bytes-total 0 :generated \"now\"}\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 1" (dataset/read-manifest root)))
      (finally (delete-tree! root)))))

(deftest sha256-streams-the-known-file-content
  (let [root (temp-dir)]
    (try
      (let [file (write-bytes! root "known.mp3" (.getBytes "hello" "UTF-8"))
            digest (java.security.MessageDigest/getInstance "SHA-256")
            expected (format "%064x" (java.math.BigInteger. 1 (.digest digest (.getBytes "hello" "UTF-8"))))]
        (is (= expected (dataset/sha256-of-file file))))
      (finally (delete-tree! root)))))

(deftest assembly-removes-deleted-source-text
  (let [repo (temp-dir)
        root (temp-dir)]
    (try
      (let [lyrics (File. repo "docs/lyrics")]
        (.mkdirs lyrics)
        (spit (File. lyrics "a.md") "A")
        (spit (File. lyrics "b.txt") "B")
        (dataset/assemble-text! repo root)
        (Files/delete (.toPath (File. lyrics "a.md")))
        (spit (File. lyrics "b.txt") "updated")
        (is (= 1 (dataset/assemble-text! repo root)))
        (is (not (.exists (File. root "text/a.md"))))
        (is (= "updated" (slurp (File. root "text/b.txt"))))
        (dataset/generate-manifest! root)
        (is (= ["text/b.txt"] (mapv :path (:entries (dataset/read-manifest root)))))
        (Files/delete (.toPath (File. lyrics "b.txt")))
        (is (zero? (dataset/assemble-text! repo root)))
        (is (not (.exists (File. root "text/b.txt")))))
      (finally (delete-tree! repo) (delete-tree! root)))))

(defn write-forms! [root forms]
  (spit (dataset/manifest-path root) (str (str/join "\n" (map pr-str forms)) "\n")))

(deftest manifest-validation-rejects-incomplete-and-invalid-data
  (with-dataset [root]
    (let [entries (:entries (dataset/read-manifest root))
          envelope {:dataset/id dataset/dataset-id :schema dataset/manifest-schema
                    :entries 3 :bytes-total 14 :generated "2026-09-11T00:00:00Z"}]
      (doseq [invalid [(assoc envelope :dataset/id "other")
                       (dissoc envelope :generated)
                       (dissoc envelope :bytes-total)
                       (assoc envelope :bytes-total 13)
                       (assoc envelope :generated nil)
                       (assoc envelope :unknown true)]]
        (write-forms! root (cons invalid entries))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 1"
                              (dataset/verify root {:hash? false})) (pr-str invalid)))
      (write-forms! root [(assoc envelope :entries 0 :bytes-total 0)])
      (is (thrown? clojure.lang.ExceptionInfo (dataset/read-manifest root)))
      (doseq [invalid [(dissoc (first entries) :sha256)
                       (assoc (first entries) :sha256 "bad")
                       (assoc (first entries) :bytes 0)
                       (assoc (first entries) :bytes "4")
                       (assoc (first entries) :path "../outside.mp3")
                       (assoc (first entries) :unknown true)
                       nil]]
        (write-forms! root (cons envelope (assoc entries 0 invalid)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 2"
                              (dataset/read-manifest root)) (pr-str invalid)))
      (write-forms! root [(assoc envelope :entries 2 :bytes-total 10)
                         (first entries) (first entries)])
      (is (thrown? clojure.lang.ExceptionInfo (dataset/read-manifest root)))
      (spit (dataset/manifest-path root)
            (str (pr-str envelope) " {}\n" (str/join "\n" (map pr-str entries)) "\n"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 1" (dataset/read-manifest root))))))

(deftest resolution-rejects-traversal-and-outside-symlinks
  (with-dataset [root]
    (doseq [path ["../outside.mp3" "absence/../../outside.mp3" "./absence/2cf24dba.mp3"
                  "/outside.mp3" "absence\\one.mp3" "C:/outside.mp3"]]
      (is (thrown? clojure.lang.ExceptionInfo (dataset/resolve-file root path)) path)))
  (let [parent (temp-dir)
        root (str parent "/dataset")
        outside (str parent "/dataset-other")]
    (try
      (dataset! root)
      (write-bytes! outside "one.mp3" (.getBytes "hello" "UTF-8"))
      (let [file (.toPath (File. root "absence/2cf24dba.mp3"))]
        (Files/delete file)
        (Files/createSymbolicLink file (.toPath (File. outside "one.mp3"))
                                  (make-array java.nio.file.attribute.FileAttribute 0)))
      (is (thrown? clojure.lang.ExceptionInfo (dataset/resolve-file root "absence/2cf24dba.mp3")))
      (is (thrown? clojure.lang.ExceptionInfo (dataset/verify root {:hash? true})))
      (is (thrown? clojure.lang.ExceptionInfo (dataset/generate-manifest! root)))
      (finally (delete-tree! parent)))))

(deftest assembly-cannot-overwrite-source-through-a-symlink
  (let [repo (temp-dir)]
    (try
      (let [lyrics (File. repo "docs/lyrics")
            text (File. repo "text")]
        (.mkdirs lyrics)
        (.mkdirs text)
        (spit (File. lyrics "a.txt") "source")
        (Files/createSymbolicLink (.toPath (File. text "a.txt"))
                                  (.toPath (File. lyrics "a.txt"))
                                  (make-array java.nio.file.attribute.FileAttribute 0))
        (is (thrown? clojure.lang.ExceptionInfo (dataset/assemble-text! repo repo)))
        (is (= "source" (slurp (File. lyrics "a.txt")))))
      (finally (delete-tree! repo)))))

(deftest ledger-verification-detects-full-hash-drift-with-equal-prefix
  (with-dataset [root]
    (let [original (dataset/entry-for (dataset/read-manifest root) "absence/2cf24dba.mp3")
          event {:event/type :track/discovered :asset :mp3 :dest "tracks/absence/2cf24dba.mp3"
                 :bytes (:bytes original) :sha256 (:sha256 original)}]
      (is (empty? (:hash-drift (dataset/verify-against-ledger root [event]))))
      (let [conflicting (str (subs (:sha256 original) 0 8) (apply str (repeat 56 "0")))
            report (dataset/verify-against-ledger root [(assoc event :sha256 conflicting)])]
        (is (empty? (:bytes-drift report)))
        (is (= [{:path "absence/2cf24dba.mp3" :expected conflicting :actual (:sha256 original)}]
               (:hash-drift report))))
      (is (empty? (:hash-drift (dataset/verify-against-ledger root [(dissoc event :sha256)])))))))

(deftest ledger-verification-checks-legacy-sha8-with-full-hash-precedence
  (with-dataset [root]
    (let [entry (dataset/entry-for (dataset/read-manifest root) "absence/2cf24dba.mp3")
          prefix (subs (:sha256 entry) 0 8)
          event {:event/type :track/discovered :asset :mp3 :dest "tracks/absence/2cf24dba.mp3"
                 :bytes (:bytes entry) :sha8 prefix}]
      (is (empty? (:hash-drift (dataset/verify-against-ledger root [event]))))
      (is (empty? (:hash-drift (dataset/verify-against-ledger
                               root [(assoc event :sha256 (:sha256 entry) :sha8 "wrong")]))))
      (let [report (dataset/verify-against-ledger root [(assoc event :sha8 "00000000")])]
        (is (empty? (:bytes-drift report)))
        (is (= [{:path "absence/2cf24dba.mp3" :expected "00000000" :actual prefix}]
               (:hash-drift report)))))))

(deftest assembly-rejects-in-root-symlinked-write-components
  (doseq [link-kind [:file :directory]]
    (let [repo (temp-dir)
          root (temp-dir)]
      (try
        (let [lyrics (File. repo "docs/lyrics")
              media (File. root "audio")
              victim (File. media "a.txt")
              text (File. root "text")]
          (.mkdirs lyrics)
          (.mkdirs media)
          (spit (File. lyrics "a.txt") "songbook")
          (spit victim "unrelated media")
          (when (= :file link-kind) (.mkdirs text))
          (Files/createSymbolicLink
           (.toPath (if (= :file link-kind) (File. text "a.txt") text))
           (.toPath (if (= :file link-kind) victim media))
           (make-array java.nio.file.attribute.FileAttribute 0))
          (is (thrown? clojure.lang.ExceptionInfo (dataset/assemble-text! repo root)) (name link-kind))
          (is (= "unrelated media" (slurp victim))))
        (finally
          ;; Remove directory links before the generic fixture cleanup walks them.
          (when (= :directory link-kind) (Files/deleteIfExists (.toPath (File. root "text"))))
          (delete-tree! repo)
          (delete-tree! root))))))

(deftest manifest-generation-refuses-stale-content-addresses
  (with-dataset [root]
    (let [before (slurp (dataset/manifest-path root))]
      (write-bytes! root "absence/2cf24dba.mp3" (.getBytes "hullo" "UTF-8"))
      (is (thrown? clojure.lang.ExceptionInfo (dataset/generate-manifest! root)))
      (is (= before (slurp (dataset/manifest-path root)))))))

(deftest manifest-generation-rejects-stray-readable-text
  (with-dataset [root]
    (let [before (slurp (dataset/manifest-path root))]
      (spit (File. root "README.md") "stray")
      (is (thrown? clojure.lang.ExceptionInfo (dataset/generate-manifest! root)))
      (is (= before (slurp (dataset/manifest-path root)))))))

(deftest ledger-verification-retains-every-historical-receipt
  (with-dataset [root]
    (let [entry (dataset/entry-for (dataset/read-manifest root) "absence/2cf24dba.mp3")
          good {:event/type :track/discovered :event/id "later" :asset :mp3
                :dest "absence/2cf24dba.mp3" :bytes 5 :sha256 (:sha256 entry)}
          bad (assoc good :event/id "earlier" :bytes 1
                     :sha256 (str "2cf24dba" (apply str (repeat 56 "0"))))
          legacy (-> good (dissoc :sha256) (assoc :event/id "legacy" :sha8 "00000000"))
          report (dataset/verify-against-ledger root [bad legacy good])]
      (is (= 1 (count (:bytes-drift report))))
      (is (= ["earlier" "legacy"] (mapv :event/id (:hash-drift report))))
      (is (= report (dataset/verify-against-ledger root [good legacy bad])))
      (is (empty? (:hash-drift (dataset/verify-against-ledger root [good good])))))))

(deftest manifest-writes-reject-symlinks-and-replace-hardlinks
  (doseq [link-kind [:symlink :hardlink]]
    (with-dataset [root]
      (let [outside (temp-dir)
            victim (File. outside "unrelated.txt")
            manifest (File. (dataset/manifest-path root))]
        (try
          (spit victim "unrelated")
          (Files/delete (.toPath manifest))
          (if (= :symlink link-kind)
            (Files/createSymbolicLink (.toPath manifest) (.toPath victim)
                                      (make-array java.nio.file.attribute.FileAttribute 0))
            (Files/createLink (.toPath manifest) (.toPath victim)))
          (if (= :symlink link-kind)
            (is (thrown? clojure.lang.ExceptionInfo (dataset/generate-manifest! root)))
            (do (dataset/generate-manifest! root)
                (is (= 3 (count (:entries (dataset/read-manifest root)))))))
          (is (= "unrelated" (slurp victim)))
          (finally
            (Files/deleteIfExists (.toPath manifest))
            (delete-tree! outside)))))))
