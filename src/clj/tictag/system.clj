(ns tictag.system
  (:require [com.stuartsierra.component :as component]
            [tictagapi.core :as tagtime]
            [tictag.tester :as tester]
            [tictag.server :as server]
            [tictag.server-chimer :as server-chimer]
            [tictag.db :as db]
            [tictag.repl :as repl]
            [tictag.figwheel :as figwheel]
            [tictag.beeminder :as beeminder]))

(defn system [config]
  (component/system-map
   :server (component/using
            (server/map->Server
             {:config (:tictag-server config)})
            [:db :tagtime :beeminder :twilio])
   :tagtime (tagtime/tagtime
             (get-in config [:tagtime :gap])
             (get-in config [:tagtime :seed]))
   :beeminder (component/using
               (beeminder/beeminder (:beeminder config))
               [:tagtime])
   :repl-server (repl/->REPL)
   :db (component/using
        (db/map->Database {:db-spec (:db config)})
        [:tagtime])
   :chimer (component/using
            (server-chimer/map->ServerChimer {})
            [:db :twilio])

   :twilio (:twilio config)

   :tester (tester/->Tester (:run-tests? config))

   :figwheel (figwheel/->Figwheel (:run-figwheel? config))))


