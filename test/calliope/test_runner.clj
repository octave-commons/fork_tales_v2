(ns calliope.test-runner
  (:require [clojure.test :as test]
            [calliope.classifier.adapters-test]
            [calliope.classifier.dsl-test]
            [calliope.classifier.main-test]
            [calliope.classifier.runtime-test]
            [calliope.law.media-test]
            [calliope.media.dataset-test]
            [calliope.media.cli-test]
            [calliope.reconstruction.handoff-test]
            [calliope.reconstruction.paths-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'calliope.classifier.adapters-test
                        'calliope.classifier.dsl-test
                         'calliope.classifier.main-test
                         'calliope.classifier.runtime-test
                         'calliope.law.media-test
                         'calliope.media.dataset-test
                         'calliope.media.cli-test
                        'calliope.reconstruction.handoff-test
                        'calliope.reconstruction.paths-test)]
    (shutdown-agents)
    (when (pos? (+ fail error))
      (System/exit 1))))
