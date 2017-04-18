(ns tictag.system
  (:require [com.stuartsierra.component :as component]
            [tictagapi.core :as tagtime]
            [tictag.tester :as tester]
            [tictag.server :as server]
            [tictag.server-chimer :as server-chimer]
            [tictag.db :as db]
            [tictag.repl :as repl]
            [tictag.scss :as scss]))

(defn system [config]
  (component/system-map
   :server (component/using
            (server/map->Server
             {:config (:tictag-server config)})
            [:db :tagtime :jwt])
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

   :scss (scss/->SCSSBuilder (:run-scss? config)
                             "resources/scss/app.scss"
                             "resources/public/css/app.css")

   :tester (tester/->Tester (:run-tests? config))))


