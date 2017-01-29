(ns tictag.main
  (:gen-class)
  (:require [tictag.server :as server]
            [tictag.server-api :as api]
            [tictag.config :refer [config]]
            [tictag.utils :as utils]
            [clojure.core.async :as a :refer [<! go-loop]]
            [taoensso.timbre :as timbre] 
            [com.stuartsierra.component :as component]
            [reloaded.repl :refer [system]]
            [tictag.server-api :refer :all]))

(defn do-not-exit! []
  (a/<!!
   (go-loop []
     (let [_ (<! (a/timeout 100000))]
       (recur)))))

(def server-system (server/system config))

(defn -main [& _]
  (reloaded.repl/set-init! (constantly (utils/system-map server-system)))
  (reloaded.repl/go)
  (do-not-exit!))

