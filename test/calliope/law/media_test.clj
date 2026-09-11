(ns calliope.law.media-test
  (:require [calliope.law.media :as media]
            [calliope.media.manifest :as manifest]
            [clojure.test :refer [deftest is]]))

(def envelope
  {:dataset/id "calliope-media"
   :schema :calliope.media/manifest-v1
   :entries 1
   :bytes-total 5
   :generated "2026-08-26T00:00:00Z"})

(def entry
  {:path "absence/aaaaaaaa.mp3"
   :bytes 5
   :sha256 (apply str (repeat 64 "a"))})

(deftest media-manifest-contracts-accept-valid-data
  (is (media/valid? :calliope.media/manifest-envelope-v1 envelope))
  (is (media/valid? :calliope.media/manifest-entry-v1 entry))
  (is (= {:dataset/id "calliope-media"
          :schema :calliope.media/manifest-v1
          :generated "2026-08-26T00:00:00Z"
          :entries [entry]}
         (media/decode-manifest {:dataset/id "calliope-media"
                                 :schema :calliope.media/manifest-v1
                                 :generated "2026-08-26T00:00:00Z"
                                 :entries [entry]}))))

(deftest media-manifest-contracts-reject-invalid-data
  (is (not (media/valid? :calliope.media/manifest-entry-v1 (assoc entry :sha256 "abc"))))
  (is (not (media/valid? :calliope.media/manifest-entry-v1 (assoc entry :path "/absence/x.mp3"))))
  (is (not (media/valid? :calliope.media/manifest-entry-v1 (assoc entry :path "absence/x.wav"))))
  (is (not (media/valid? :calliope.media/manifest-entry-v1 (assoc entry :bytes 0))))
  (is (not (media/valid? :calliope.media/manifest-entry-v1 (assoc entry :bytes -1))))
  (is (not (media/valid? :calliope.media/manifest-envelope-v1 (assoc envelope :schema :wrong))))
  (is (map? (media/decode-manifest (assoc envelope :entries [entry])))))

(deftest media-manifest-contracts-cover-text-and-metadata-content
  (doseq [path ["absence/aaaaaaaa.json" "text/song.md" "text/song.txt"]]
    (is (media/valid? :calliope.media/manifest-entry-v1 (assoc entry :path path))
        path)))

(deftest runtime-and-law-validation-agree
  (doseq [value [envelope (dissoc envelope :generated)
                 (dissoc envelope :bytes-total) (assoc envelope :dataset/id "other")
                 (assoc envelope :entries 0) (assoc envelope :unknown true)]]
    (is (= (media/valid? :calliope.media/manifest-envelope-v1 value)
           (manifest/envelope? value))))
  (doseq [path ["absence/aaaaaaaa.mp3" "absence/aaaaaaaa.MP3" "text/song.txt"
                "../outside.mp3" "./song.mp3" "a/../../song.mp3"
                "/song.mp3" "C:/song.mp3" "a\\song.mp3" "a//song.mp3"
                "a/./song.mp3" "a/../song.mp3" "a/song.mp3/"
                "a/line\nsong.mp3"]]
    (let [value (assoc entry :path path)]
      (is (= (media/valid? :calliope.media/manifest-entry-v1 value)
             (manifest/entry? value)) path)))
  (doseq [value [(dissoc entry :sha256) (assoc entry :sha256 "bad")
                 (assoc entry :bytes 0) (assoc entry :unknown true) nil]]
    (is (false? (media/valid? :calliope.media/manifest-entry-v1 value)))
    (is (false? (manifest/entry? value)))))

(deftest media-content-addresses-match-the-full-hash
  (doseq [extension ["mp3" "jpeg" "json"]
          path [(str "absence/bbbbbbbb." extension) (str "absence/friendly." extension)]]
    (let [value (assoc entry :path path)]
      (is (false? (media/valid? :calliope.media/manifest-entry-v1 value)))
      (is (false? (manifest/entry? value)))))
  (doseq [path ["text/friendly.md" "text/friendly.txt"]]
    (is (manifest/entry? (assoc entry :path path)))))
