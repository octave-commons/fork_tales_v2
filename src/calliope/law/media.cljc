(ns calliope.law.media
  "Malli contracts for the manifest-addressed Calliope media dataset."
  (:require [calliope.media.manifest :as manifest]
            [malli.core :as m]
            [malli.registry :as mr]))

(def registry
  {:calliope.media/manifest-envelope-v1
   [:map {:closed true}
    [:dataset/id [:= manifest/dataset-id]]
    [:schema [:= :calliope.media/manifest-v1]]
    [:entries [:int {:min 1}]]
    [:bytes-total [:int {:min 0}]]
    [:generated :string]]

   :calliope.media/manifest-entry-v1
    [:map {:closed true}
     [:path [:fn manifest/content-path?]]
     [:bytes [:int {:min 1}]]
     [:sha256 [:and :string [:re manifest/sha256-pattern]]]]

   :calliope.media/manifest-v1
   [:map {:closed true}
    [:dataset/id [:= manifest/dataset-id]]
    [:schema [:= :calliope.media/manifest-v1]]
    [:entries [:vector {:min 1} [:ref :calliope.media/manifest-entry-v1]]]
    [:bytes-total {:optional true} [:int {:min 0}]]
    [:generated :string]]})

(def malli-registry
  (mr/composite-registry m/default-registry registry))

(defn schema
  "Resolve a named media dataset contract."
  [schema-key]
  (m/schema [:ref schema-key] {:registry malli-registry}))

(defn valid?
  "Does a parsed media manifest satisfy its contract?"
  ([value]
   (valid? :calliope.media/manifest-v1 value))
  ([schema-key value]
   (m/validate (schema schema-key) value)))

(defn explain
  "Return Malli explain data for an invalid parsed media manifest."
  ([value]
   (explain :calliope.media/manifest-v1 value))
  ([schema-key value]
   (m/explain (schema schema-key) value)))

(defn decode-manifest
  "Return a validated parsed manifest, or Malli explain data on failure."
  [value]
  (if (valid? value) value (explain value)))
