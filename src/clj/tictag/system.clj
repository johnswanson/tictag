(ns tictag.system
  (:require [com.stuartsierra.component :as component]
            [tictag.re-frame :as re-frame]
            [tictagapi.core :as tagtime]
            [tictag.tester :as tester]
            [tictag.server :as server]
            [tictag.server-chimer :as server-chimer]
            [tictag.db :as db]
            [tictag.repl :as repl]
            [tictag.beeminder :as beeminder]
            [tictag.scss :as scss]
            [tictag.google :as google]
            [tictag.slack :as slack]))

(defn system [config]
  (component/system-map
   :re-frame (component/using
              (re-frame/map->Reframe
               {:shared-secret (:shared-secret config)})
              [:db :beeminder :tagtime :twilio :calendar :slack])
   :server (component/using
            (server/map->Server
             {:config (:tictag-server config)})
            [:re-frame :db :tagtime])
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
            [:db :twilio :slack])

   :twilio (:twilio config)

   :calendar (component/using
              (google/map->EventInserter {:config (:google config)})
              [:db])

   :scss (scss/->SCSSBuilder (:run-scss? config)
                             "resources/scss/app.scss"
                             "resources/public/css/app.css")

   :slack (component/using
           (slack/map->Slack {:config (:slack config)})
           [:db :tagtime :beeminder :twilio :calendar])

   :tester (tester/->Tester (:run-tests? config))))


