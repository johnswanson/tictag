(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.repl :refer :all]
            [reloaded.repl :refer [system init start stop go reset]]
            [com.stuartsierra.component :as component]
            [clj-time.core :as t]
            [tictag.server :as server]
            [tictag.client :as client]
            [tictag.config :as config :refer [config]]
            [tictag.db :as db]
            [tictag.beeminder :as bm]))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn dev-system []
  (component/system-map
   :client-chimer (client/->ClientChimer)
   :server (component/using
            (server/map->Server
             {:config config/server})
            [:db])
   :db (db/->Database (:server-db-file config/config))
   :chimer (component/using
            (server/map->ServerChimer {})
            [:db])))

(reloaded.repl/set-init! dev-system)
