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
            :missing (Files/delete (.toPath (File. root "absence/one.mp3")))
            :size (spit (File. root "absence/one.mp3") "x")
            :hash (spit (File. root "absence/one.mp3") "hullo")
            :manifest (spit (dataset/manifest-path root)
                            "{:schema :calliope.media/manifest-v1 :entries 0}\n"))
          (let [result (run! "sync" "--remote" "fixture:media")]
            (is (not (zero? (:exit result))) (str change " " result))
            (is (not (.exists log)) (str change " must not invoke rclone")))
          (Files/deleteIfExists (.toPath log)))
        (testing "ledger hashes reject a regenerated, same-size change"
          (fixture/dataset! root)
          (let [entry (dataset/entry-for (dataset/read-manifest root) "absence/one.mp3")
                ledger (File. repo "ledgers/ingest.edn")]
            (.mkdirs (.getParentFile ledger))
            (spit ledger (pr-str {:event/type :track/discovered :asset :mp3
                                 :dest "tracks/absence/one.mp3"
                                 :bytes (:bytes entry) :sha256 (:sha256 entry)}))
            (is (zero? (:exit (run! "verify" "--ledger"))))
            (spit (File. root "absence/one.mp3") "hullo")
            (dataset/generate-manifest! root)
            (let [result (run! "verify" "--ledger")]
              (is (not (zero? (:exit result))))
              (is (re-find #":hash-drift" (:out result)))))))
      (finally (fixture/delete-tree! repo)))))
