(ns calliope.media.ingestion-test
  "Run the actual Babashka track-ingestion path against disposable source files."
  (:require [calliope.media.dataset :as dataset]
            [calliope.media.dataset-test :as fixture]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io File]
           [java.nio.file Files]))

(deftest track-ingestion-records-only-verified-contained-bytes
  (doseq [scenario [:new :intact :truncated :same-size :directory-link :file-link]]
    (let [repo (fixture/temp-dir)
          root (str repo "/tracks")
          input (File. repo "input")
          source (File. input "source.mp3")
          outside (File. repo "outside")
          victim (File. outside "victim.mp3")
          target (File. root "song/2cf24dba.mp3")
          ledger (File. repo "ledgers/ingest.edn")
          script (File. repo "scripts/corpus.clj")
          classpath (.getCanonicalPath (io/file "src"))]
      (try
        (.mkdirs input)
        (.mkdirs outside)
        (spit source "hello")
        (spit victim "unrelated")
        (.mkdirs (.getParentFile script))
        (io/copy (io/file "scripts/corpus.clj") script)
        (let [index (File. repo "ledgers/projections/songs-v1.edn")]
          (.mkdirs (.getParentFile index))
          (spit index (pr-str {"song" {:sources [(str source)]}})))
        (spit ledger "{:event/type :fixture/anchor}\n")
        (.mkdirs (File. root))
        (if (= :directory-link scenario)
          (Files/createSymbolicLink (.toPath (File. root "song")) (.toPath outside)
                                    (make-array java.nio.file.attribute.FileAttribute 0))
          (.mkdirs (.getParentFile target)))
        (case scenario
          :intact (spit target "hello")
          :truncated (spit target "x")
          :same-size (spit target "hullo")
          :file-link (Files/createSymbolicLink (.toPath target) (.toPath victim)
                                              (make-array java.nio.file.attribute.FileAttribute 0))
          nil)
        (let [program (str "(binding [*command-line-args* []] (load-file " (pr-str (str script)) "))\n"
                           "(with-redefs [suno-dirs (constantly " (pr-str [(str input)]) ")] (tracks!))")
              result (shell/sh "bb" "--classpath" classpath "-e" program
                               :dir repo :env (assoc (into {} (System/getenv)) "CALLIOPE_MEDIA_ROOT" root))
              events (mapv edn/read-string (str/split-lines (slurp ledger)))
              discoveries (filter #(= :track/discovered (:event/type %)) events)]
          (is (= {:event/type :fixture/anchor} (first events)) (name scenario))
          (is (= "unrelated" (slurp victim)) (name scenario))
          (if (contains? #{:new :intact} scenario)
            (do
              (is (zero? (:exit result)) (str scenario " " result))
              (is (= 1 (count discoveries)))
              (is (= (dataset/sha256-of-file target) (:sha256 (first discoveries))))
              (is (= 5 (:bytes (first discoveries))))
              (is (:ok (dataset/verify root {:hash? true}))))
            (do
              (is (not (zero? (:exit result))) (str scenario " " result))
              (is (empty? discoveries) "No receipt may claim unverified source bytes")
              (is (not (.exists (File. root dataset/manifest-name))))
              (when (= :directory-link scenario)
                (is (not (.exists (File. outside "2cf24dba.mp3")))))
              (when (contains? #{:truncated :same-size} scenario)
                (is (= (if (= :truncated scenario) "x" "hullo") (slurp target)))))))
        (finally
          (when (= :directory-link scenario)
            (Files/deleteIfExists (.toPath (File. root "song"))))
          (fixture/delete-tree! repo))))))

(deftest external-ingestion-updates-tracked-metadata-before-completion
  (doseq [scenario [:new :json-link :manifest-link]]
    (let [repo (fixture/temp-dir)
          root (fixture/temp-dir)
          tracked (str repo "/tracks")
          input (File. repo "input")
          audio (File. input "source.mp3")
          metadata (File. input "source.json")
          victim (File. repo "victim")
          ledger (File. repo "ledgers/ingest.edn")
          script (File. repo "scripts/corpus.clj")
          classpath (.getCanonicalPath (io/file "src"))]
      (try
        (fixture/dataset! root)
        (fixture/write-bytes! root "text/song.md" (.getBytes "song" "UTF-8"))
        (.mkdirs input)
        (spit audio "hello")
        (spit metadata "{\"title\":\"song\"}")
        (spit victim "unrelated")
        (fixture/write-bytes! tracked "retired/nested/deadbeef.json" (.getBytes "stale" "UTF-8"))
        (.mkdirs (.getParentFile script))
        (io/copy (io/file "scripts/corpus.clj") script)
        (let [index (File. repo "ledgers/projections/songs-v1.edn")]
          (.mkdirs (.getParentFile index))
          (spit index (pr-str {"song" {:sources [(str audio)]}})))
        (spit ledger "{:event/type :fixture/anchor}\n")
        (let [json-path (str "song/" (subs (dataset/sha256-of-file metadata) 0 8) ".json")
              json-target (File. tracked json-path)
              manifest-target (File. tracked dataset/manifest-name)]
          (when (not= :new scenario)
            (.mkdirs (.getParentFile json-target))
            (Files/createSymbolicLink
             (.toPath (if (= :json-link scenario) json-target manifest-target))
             (.toPath victim) (make-array java.nio.file.attribute.FileAttribute 0)))
          (let [program (str "(binding [*command-line-args* []] (load-file " (pr-str (str script)) "))\n"
                             "(with-redefs [suno-dirs (constantly " (pr-str [(str input)]) ")] (tracks!))")
                result (shell/sh "bb" "--classpath" classpath "-e" program
                                 :dir repo :env (assoc (into {} (System/getenv)) "CALLIOPE_MEDIA_ROOT" root))
                events (mapv edn/read-string (str/split-lines (slurp ledger)))
                completed (filter #(= :tracks/run-completed (:event/type %)) events)]
            (is (= {:event/type :fixture/anchor} (first events)))
            (is (= "unrelated" (slurp victim)))
            (if (= :new scenario)
              (do
                (is (zero? (:exit result)) (str result))
                (is (= 1 (count completed)))
                (is (= (slurp (File. root dataset/manifest-name)) (slurp manifest-target)))
                (is (= (slurp metadata) (slurp json-target)))
                (is (= "meta" (slurp (File. tracked "absence/ea3bd73e.json"))))
                (is (not (.exists (File. tracked "retired/nested/deadbeef.json"))))
                (is (not (.exists (File. tracked "song/2cf24dba.mp3"))))
                (is (not (.exists (File. tracked "absence/486ea462.jpeg"))))
                (is (not (.exists (File. tracked "text/song.md"))))
                (is (:ok (dataset/verify root {:hash? true}))))
              (do
                (is (not (zero? (:exit result))) (str result))
                (is (empty? completed) "A failed metadata projection cannot complete ingestion")))))
        (finally
          (fixture/delete-tree! repo)
          (fixture/delete-tree! root))))))
