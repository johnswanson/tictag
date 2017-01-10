(ns tictag.main
  (:gen-class)
  (:require [tictag.server :as server]
            [tictag.client :as client]
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
  (timbre/debugf "Config: %s" (println tictag.config/config))
  (let [system (case (first args)
                 "server" server/system
                 "client" client/system)]
    (component/start system)
    (do-not-exit!)))

