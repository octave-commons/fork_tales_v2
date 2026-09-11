(ns calliope.media.cli-test
  "Execute the real Babashka CLI against disposable datasets and a recording rclone."
  (:require [calliope.media.dataset :as dataset]
            [calliope.media.dataset-test :as fixture]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.nio.file Files]))

(deftest cli-sync-and-ledger-integrity-boundaries
  (let [repo (fixture/temp-dir)
        root (str repo "/tracks")
        bin (str repo "/bin")
        log (File. repo "rclone.log")
        script (File. repo "scripts/media.clj")
        source (.getCanonicalPath (io/file "src"))]
    (try
      (.mkdirs (.getParentFile script))
      (io/copy (io/file "scripts/media.clj") script)
      (spit (File. repo "deps.edn") "{}")
      (.mkdirs (File. bin))
      (let [rclone (File. bin "rclone")]
        (spit rclone "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$CALLIOPE_RCLONE_LOG\"\n")
        (.setExecutable rclone true))
      (fixture/dataset! root)
      (let [env (merge (into {} (System/getenv))
                       {"CALLIOPE_MEDIA_ROOT" root "CALLIOPE_RCLONE_LOG" (str log)
                        "PATH" (str bin File/pathSeparator (System/getenv "PATH"))})
            run! (fn [& args]
                   (apply shell/sh "bb" "--classpath" source (str script)
                          (concat args [:dir repo :env env])))
            pristine (slurp (dataset/manifest-path root))]
        (testing "intact files reach rclone"
          (let [result (run! "sync" "--remote" "fixture:media")]
            (is (zero? (:exit result)) (pr-str result))
            (is (.exists log)))
          (Files/deleteIfExists (.toPath log)))
        (doseq [change [:missing :size :hash :manifest]]
          (fixture/dataset! root)
          (spit (dataset/manifest-path root) pristine)
          (case change
            :missing (Files/delete (.toPath (File. root "absence/2cf24dba.mp3")))
            :size (spit (File. root "absence/2cf24dba.mp3") "x")
            :hash (spit (File. root "absence/2cf24dba.mp3") "hullo")
            :manifest (spit (dataset/manifest-path root)
                            "{:schema :calliope.media/manifest-v1 :entries 0}\n"))
          (let [result (run! "sync" "--remote" "fixture:media")]
            (is (not (zero? (:exit result))) (str change " " result))
            (is (not (.exists log)) (str change " must not invoke rclone")))
          (Files/deleteIfExists (.toPath log)))
        (testing "ledger hashes reject contradictory discovery receipts"
         (doseq [hash-key [:sha256 :sha8]]
          (fixture/dataset! root)
          (let [entry (dataset/entry-for (dataset/read-manifest root) "absence/2cf24dba.mp3")
                ledger (File. repo "ledgers/ingest.edn")]
            (.mkdirs (.getParentFile ledger))
            (spit ledger (pr-str {:event/type :track/discovered :asset :mp3
                                 :dest "tracks/absence/2cf24dba.mp3"
                                 :bytes (:bytes entry)
                                 hash-key (if (= :sha8 hash-key)
                                            (subs (:sha256 entry) 0 8)
                                            (:sha256 entry))}))
            (is (zero? (:exit (run! "verify" "--ledger"))))
            (spit ledger (pr-str {:event/type :track/discovered :asset :mp3
                                 :dest "tracks/absence/2cf24dba.mp3" :bytes (:bytes entry)
                                 hash-key (if (= :sha8 hash-key) "00000000"
                                            (str (subs (:sha256 entry) 0 8)
                                                 (apply str (repeat 56 "0"))))}))
            (let [result (run! "verify" "--ledger")]
              (is (not (zero? (:exit result))))
              (is (re-find #":hash-drift" (:out result)))))))
        (testing "manifest regeneration cannot bless changed content-addressed bytes"
          (fixture/dataset! root)
          (let [before (slurp (dataset/manifest-path root))]
            (spit (File. root "absence/2cf24dba.mp3") "hullo")
            (is (not (zero? (:exit (run! "manifest")))))
            (is (= before (slurp (dataset/manifest-path root))))
            (is (not (zero? (:exit (run! "sync" "--remote" "fixture:media")))))))
        (testing "Babashka assembly rejects an in-root media alias"
          (let [lyrics (File. repo "docs/lyrics")
                copy (File. root "text/a.txt")
                victim (File. root "absence/2cf24dba.mp3")
                before (slurp victim)]
            (.mkdirs lyrics)
            (spit (File. lyrics "a.txt") "songbook")
            (is (zero? (:exit (run! "assemble"))))
            (is (= "songbook" (slurp copy)))
            (Files/delete (.toPath copy))
            (Files/createSymbolicLink (.toPath copy) (.toPath victim)
                                      (make-array java.nio.file.attribute.FileAttribute 0))
            (is (not (zero? (:exit (run! "assemble")))))
            (is (= before (slurp victim))))))
      (finally (fixture/delete-tree! repo)))))
