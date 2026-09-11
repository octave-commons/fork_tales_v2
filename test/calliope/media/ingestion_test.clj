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
