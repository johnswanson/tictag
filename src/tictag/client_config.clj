(ns tictag.client-config
  (:require [environ.core :refer [env]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def config-file (format "%s/.tictag.secret" (System/getProperty "user.home")))

(defn shared-secret []
  (or (:tictag-shared-secret env)
      (try (slurp (io/file config-file)) (catch Exception e nil))
      "FIXME"))

(defn remote-url [& [url]]
  (or url (:tictag-server-url env)))
