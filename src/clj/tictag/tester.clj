(ns tictag.tester
  (:require  [clojure.test :refer :all]
             [com.stuartsierra.component :as component]))

(defrecord Tester [test?]
  component/Lifecycle
  (start [component]
    (if test?
      (assoc component :tests (run-all-tests))
      component))
  (stop [component]
    (dissoc component :tests)))
