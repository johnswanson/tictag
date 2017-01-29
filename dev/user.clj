(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [taoensso.timbre :as timbre]
            [reloaded.repl :refer [system init start stop go reset]]
            [com.stuartsierra.component :as component]
            [tictag.config :as config]
            [tictag.server :as server]
            [tictag.server-api :refer :all]
            [tictag.system]
            [tictag.utils :as utils]
            [clojure.test :refer :all]
            [tictag.server-test :as server-test]))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src/clj" "test/clj")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(reloaded.repl/set-init! #(tictag.system/system config/config))

