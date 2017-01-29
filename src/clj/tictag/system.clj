(ns tictag.system
  (:require [com.stuartsierra.component :as component]
            [tictagapi.core :as tagtime]
            [tictag.tester :as tester]
            [tictag.server :as server]
            [tictag.server-chimer :as server-chimer]
            [tictag.db :as db]
            [tictag.repl :as repl]
            [tictag.figwheel :as figwheel]))

(defn system [config]
  (component/system-map
   :server (component/using
            (server/map->Server
             {:config (:tictag-server config)})
            [:db :tagtime])
   :tagtime (tagtime/tagtime
             (get-in config [:tagtime :gap])
             (get-in config [:tagtime :seed]))
   :repl-server (repl/->REPL)
   :db (component/using
        (db/map->Database {:file (get-in config [:db :file])})
        [:tagtime])
   :chimer (component/using
            (server-chimer/map->ServerChimer
             {:config (:twilio config)})
            [:db])

   :tester (tester/->Tester (:run-tests? config))

   :figwhel (figwheel/->Figwheel (:run-figwheel? config))))


