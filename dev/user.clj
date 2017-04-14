(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [taoensso.timbre :as timbre]
            [reloaded.repl :refer [system init start stop go reset]]
            [com.stuartsierra.component :as component]
            [tictag.config :refer [config]]
            [tictag.server :as server]
            [tictag.db :as db]
            [tictag.beeminder :as bm]
            [tictag.system]
            [clojure.test :refer :all]
            [tictag.server-test :as server-test]
            [tictag.ragtime :as ragtime]
            [ragtime.repl :refer [migrate rollback]]
            [honeysql.core :as sql]
            [honeysql.helpers :refer :all]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all]
            [clojure.java.jdbc :as j]
            [tictag.crypto :as crypto]
            [environ.core :refer [env]]))

(defn migrate! [] (migrate ragtime/config))
(defn rollback! [] (rollback ragtime/config))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src/clj" "test/clj")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(reloaded.repl/set-init! #(tictag.system/system config))

(defn dev-user [] (db/get-user (:db system) "j"))
