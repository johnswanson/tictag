(ns tictag.system
  (:require [com.stuartsierra.component :as component]
            [tictagapi.core :as tagtime]
            [tictag.tester :as tester]
            [tictag.server :as server]
            [tictag.ws :as ws]
            [tictag.server-chimer :as server-chimer]
            [tictag.db :as db]
            [tictag.repl :as repl]
            [tictag.logging :as logging]
            [tictag.rollcage :as rollcage]))

(logging/configure!)

(defn system [config]
  (component/system-map
   :server (component/using
            (server/map->Server
             {:config (:tictag-server config)})
            [:db :tagtime :jwt :beeminder :slack :ws])

   :ws (ws/->Sente)
   :tagtime (tagtime/tagtime
             (get-in config [:tagtime :gap])
             (get-in config [:tagtime :seed]))
   :repl-server (repl/->REPL)

   :jwt (config :jwt)

   :db (component/using
        (db/map->Database {:db-spec    (:db config)
                           :crypto-key (:crypto-key config)})
        [:tagtime])

   :chimer (component/using
            (server-chimer/map->ServerChimer {})
            [:db])

   :rollcage (rollcage/map->RollcageClient (:rollcage config))

   :beeminder (component/using {} [:db :tagtime])
   :slack {}

   :tester (tester/->Tester (:run-tests? config))))


