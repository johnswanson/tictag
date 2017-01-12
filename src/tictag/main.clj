(ns tictag.main
  (:gen-class)
  (:require [tictag.server :as server]
            [tictag.client :as client]
            [tictag.client-config :as client-config]
            [tictag.utils :as utils]
            [tictag.config]
            [clojure.core.async :as a :refer [<! go-loop]]
            [taoensso.timbre :as timbre] 
            [com.stuartsierra.component :as component]))

(defn do-not-exit! []
  (a/<!!
   (go-loop []
     (let [_ (<! (a/timeout 100000))]
       (recur)))))

(defn -main [& args]
  (let [[system-type remote-url] args
        system                   (case system-type
                                   "server" server/system
                                   "client" (client/system (client-config/remote-url remote-url)))]
    (component/start (utils/system-map system))
    (do-not-exit!)))

