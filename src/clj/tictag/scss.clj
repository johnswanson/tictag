(ns tictag.scss
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [juxt.dirwatch]))

(defn compile-scss [in out]
  (fn [& _]
    (timbre/debug "Building scss")
    (sh "sassc"
        "-m"
        "-I" "resources/scss"
        "-t" "nested"
        in
        out)))

(defrecord SCSSBuilder [run? in out]
  component/Lifecycle
  (start [component]
    (timbre/debugf "Starting SCSS builder? %s" run?)
    (when run?
      (assoc component
             :watcher
             (juxt.dirwatch/watch-dir (compile-scss in out)
                                      (io/file "resources/scss")))))
  (stop [component]
    (when-let [watcher (:watcher component)]
      (juxt.dirwatch/close-watcher watcher))
    (dissoc component :watcher)))
