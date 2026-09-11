(ns calliope.media.manifest
  "Dependency-free manifest predicates shared by filesystem readers and Malli laws."
  (:require [clojure.string :as str]))

(def dataset-id "calliope-media")
(def schema :calliope.media/manifest-v1)
(def content-path-pattern #"\.(mp3|jpeg|json|md|txt)$")
(def sha256-pattern #"^[0-9a-f]{64}$")

(defn relative-path?
  "True for a non-empty POSIX relative path without traversal or control characters."
  [path]
  (and (string? path)
       (not (str/blank? path))
       (not (re-find #"[\\\s:\x00-\x1f\x7f]" path))
       (every? #(not (contains? #{"" "." ".."} %)) (str/split path #"/" -1))))

(defn content-path?
  "True for a safe relative path with a supported content extension."
  [path]
  (and (relative-path? path) (boolean (re-find content-path-pattern (str/lower-case path)))))

(defn envelope?
  "Validate the closed on-disk envelope without loading Malli in Babashka."
  [value]
  (and (map? value)
       (= #{:dataset/id :schema :entries :bytes-total :generated} (set (keys value)))
       (= dataset-id (:dataset/id value))
       (= schema (:schema value))
       (int? (:entries value)) (pos? (:entries value))
       (int? (:bytes-total value)) (<= 0 (:bytes-total value))
       (string? (:generated value))))

(defn addressed-entry?
  "Media and metadata basenames carry their SHA-8; songbook text keeps its name."
  [{:keys [path sha256]}]
  (and (string? path) (string? sha256)
       (boolean (re-matches sha256-pattern sha256))
       (or (boolean (re-find #"\.(md|txt)$" (str/lower-case path)))
           (let [prefix (second (re-find #"(?:^|/)([0-9a-f]{8})\.[^.]+$" path))]
             (and (some? prefix) (= prefix (subs sha256 0 8)))))))

(defn entry?
  "Validate one closed manifest entry."
  [value]
  (and (map? value)
       (= #{:path :bytes :sha256} (set (keys value)))
       (content-path? (:path value))
       (int? (:bytes value)) (pos? (:bytes value))
       (string? (:sha256 value))
       (boolean (re-matches sha256-pattern (:sha256 value)))
       (addressed-entry? value)))
