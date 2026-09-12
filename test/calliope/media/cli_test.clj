(ns calliope.media.cli-test
  "Execute the real Babashka CLI against disposable datasets and a recording rclone."
  (:require [calliope.media.dataset :as dataset]
            [calliope.media.dataset-test :as fixture]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]
           [java.nio.file Files]))

(defn discovery-events [root]
  (mapv (fn [{:keys [path] :as entry}]
          (assoc entry :event/type :track/discovered :dest path
                 :asset (keyword (last (str/split path #"\.")))))
        (:entries (dataset/read-manifest root))))

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
        (testing "missing and unknown commands fail; explicit help succeeds"
          (is (not (zero? (:exit (run!)))))
          (is (not (zero? (:exit (run! "verfiy")))))
          (is (zero? (:exit (run! "--help"))))
          (is (not (.exists log))))
        (testing "remote grammar rejects typos, incomplete flags and extra values"
          (doseq [command ["sync" "check"]
                  args [["--remtoe" "fixture:media"] ["--remote"]
                        ["--remote" "--ledger"] ["--remote" ""]
                        ["--remote" "fixture:media" "extra"]
                        ["--remote" "fixture:media" "--remote" "second:media"]]]
            (is (not (zero? (:exit (apply run! command args)))))
            (is (not (.exists log)) "Malformed remote arguments must never invoke rclone"))
          (is (not (zero? (:exit (run! "verify" "--ledgre"))))))
        (testing "invalid environment destinations cannot become a local path or option"
          (doseq [value ["" "--help"]]
            (let [result (shell/sh "bb" "--classpath" source (str script) "sync"
                                   :dir repo :env (assoc env "CALLIOPE_MEDIA_REMOTE" value))]
              (is (not (zero? (:exit result))))
              (is (not (.exists log))))))
        (testing "intact files reach rclone"
          (let [result (run! "sync" "--remote" "fixture:media")]
            (is (zero? (:exit result)) (pr-str result))
            (is (.exists log)))
          (Files/deleteIfExists (.toPath log)))
        (testing "incomplete roots preserve the prior catalog unless removal is explicit"
          (Files/delete (.toPath (File. root "absence/2cf24dba.mp3")))
          (is (not (zero? (:exit (run! "manifest")))))
          (is (= pristine (slurp (dataset/manifest-path root))))
          (is (zero? (:exit (run! "manifest" "--allow-removals"))))
          (is (nil? (dataset/entry-for (dataset/read-manifest root) "absence/2cf24dba.mp3")))
          (is (zero? (:exit (run! "verify")))))
        (doseq [change [:missing :size :hash :manifest :timestamp]]
          (fixture/dataset! root)
          (spit (dataset/manifest-path root) pristine)
          (case change
            :missing (Files/delete (.toPath (File. root "absence/2cf24dba.mp3")))
            :size (spit (File. root "absence/2cf24dba.mp3") "x")
            :hash (spit (File. root "absence/2cf24dba.mp3") "hullo")
            :timestamp (spit (dataset/manifest-path root)
                             (str/replace pristine "2026-08-26T00:00:00Z" "now"))
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
                ledger (File. repo "ledgers/ingest.edn")
                other-receipts (remove #(= "absence/2cf24dba.mp3" (:dest %)) (discovery-events root))
                add-other-receipts! #(spit ledger (str "\n" (str/join "\n" (map pr-str other-receipts)) "\n") :append true)]
            (.mkdirs (.getParentFile ledger))
            (spit ledger (pr-str {:event/type :track/discovered :asset :mp3
                                 :dest "tracks/absence/2cf24dba.mp3"
                                 :bytes (:bytes entry)
                                 hash-key (if (= :sha8 hash-key)
                                            (subs (:sha256 entry) 0 8)
                                            (:sha256 entry))}))
            (add-other-receipts!)
            (is (zero? (:exit (run! "verify" "--ledger"))))
            (spit ledger (pr-str {:event/type :track/discovered :asset :mp3
                                 :dest "tracks/absence/2cf24dba.mp3" :bytes (:bytes entry)
                                 hash-key (if (= :sha8 hash-key) "00000000"
                                            (str (subs (:sha256 entry) 0 8)
                                                 (apply str (repeat 56 "0"))))}))
            (add-other-receipts!)
            (let [result (run! "verify" "--ledger")]
              (is (not (zero? (:exit result))))
              (is (re-find #":hash-drift" (:out result)))))))
        (testing "ledger verification requires media receipts but excludes songbook text"
          (fixture/dataset! root)
          (let [ledger (File. repo "ledgers/ingest.edn")
                events (discovery-events root)]
            (fixture/write-bytes! root "text/song.md" (.getBytes "song" "UTF-8"))
            (dataset/generate-manifest! root)
            (spit ledger (str/join "\n" (map pr-str events)))
            (is (zero? (:exit (run! "verify" "--ledger"))))
            (doseq [omitted events]
              (spit ledger (str/join "\n" (map pr-str (remove #{omitted} events))))
              (let [result (run! "verify" "--ledger")]
                (is (not (zero? (:exit result))))
                (is (str/includes? (:out result) (:dest omitted)))))))
        (testing "invalid ledger forms cannot terminate parsing before later receipts"
          (fixture/dataset! root)
          (let [ledger (File. repo "ledgers/ingest.edn")
                entry (dataset/entry-for (dataset/read-manifest root) "absence/2cf24dba.mp3")
                event (assoc entry :event/type :track/discovered :asset :mp3 :dest (:path entry))]
            (doseq [invalid [nil false 7 [] {} {:event/type "invalid"}]]
              (spit ledger (str (pr-str event) "\n" (pr-str invalid) "\n"
                                (pr-str (assoc event :bytes 999)) "\n"))
              (let [result (run! "verify" "--ledger")]
                (is (not (zero? (:exit result))))
                (is (re-find #"Invalid ledger event form" (:err result)))))))
        (testing "manifest regeneration cannot bless changed content-addressed bytes"
          (fixture/dataset! root)
          (let [before (slurp (dataset/manifest-path root))]
            (spit (File. root "absence/2cf24dba.mp3") "hullo")
            (is (not (zero? (:exit (run! "manifest")))))
            (is (= before (slurp (dataset/manifest-path root))))
            (is (not (zero? (:exit (run! "sync" "--remote" "fixture:media")))))))
        (testing "a later receipt cannot conceal an earlier contradiction"
          (fixture/dataset! root)
          (let [entry (dataset/entry-for (dataset/read-manifest root) "absence/2cf24dba.mp3")
                good {:event/type :track/discovered :event/id "later" :asset :mp3
                      :dest "absence/2cf24dba.mp3" :bytes 5 :sha256 (:sha256 entry)}
                bad (assoc good :event/id "earlier" :bytes 1)]
            (spit (File. repo "ledgers/ingest.edn") (str (pr-str bad) "\n" (pr-str good) "\n"))
            (let [result (run! "verify" "--ledger")]
              (is (not (zero? (:exit result))))
              (is (re-find #"earlier" (:out result))))))
        (testing "the manifest command cannot follow an outside symlink"
          (let [manifest (File. (dataset/manifest-path root))
                victim (File. repo "unrelated.txt")]
            (spit victim "unrelated")
            (Files/delete (.toPath manifest))
            (Files/createSymbolicLink (.toPath manifest) (.toPath victim)
                                      (make-array java.nio.file.attribute.FileAttribute 0))
            (is (not (zero? (:exit (run! "manifest")))))
            (is (= "unrelated" (slurp victim)))
            (Files/delete (.toPath manifest))))
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
