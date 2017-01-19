(ns tictag.client-config
  (:require [environ.core :refer [env]]))

(def config
  {:remote-url    (env :tictag-server-url)
   :shared-secret (env :tictag-shared-secret)})
