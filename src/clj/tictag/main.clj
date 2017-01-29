(ns tictag.main
  (:gen-class)
  (:require [tictag.system]
            [tictag.config :refer [config]]
            [clojure.core.async :as a :refer [<! go-loop]]
            [com.stuartsierra.component :as component]
            [reloaded.repl :refer [system]]))

(defn do-not-exit! []
  (a/<!!
   (go-loop []
     (let [_ (<! (a/timeout 100000))]
       (recur)))))

(def server-system (tictag.system/system config))

(defn -main [& _]
  (reloaded.repl/set-init! (constantly server-system))
  (reloaded.repl/go)
  (do-not-exit!))

