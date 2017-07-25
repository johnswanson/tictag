(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [taoensso.timbre :as timbre]
            [reloaded.repl :refer [system init start stop go reset]]
            [com.stuartsierra.component :as component]
            [tictag.config :refer [config]]
            [tictag.utils :as utils]
            [tictag.slack :as slack]
            [tictag.server :as server :refer [evaluate]]
            [tictag.server-chimer :refer [chime!]]
            [tictag.db :as db]
            [tictag.beeminder :as bm]
            [tictag.system]
            [clojure.test :refer :all]
            [tictag.server-test :as server-test]
            [tictag.ragtime :as ragtime]
            [tictag.tagtime :as tt]
            [ragtime.repl :refer [migrate rollback]]
            [clojure.repl :refer [doc]]
            [honeysql.core :as sql]
            [honeysql.helpers :refer [insert-into values limit where from select join]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer [upsert do-nothing on-conflict do-update-set]]
            [clojure.java.jdbc :as j]
            [tictag.crypto :as crypto]
            [environ.core :refer [env]]
            [figwheel-sidecar.repl-api]
            [instaparse.core :as insta]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(defn chime-test! []
  ((chime! system) (t/now)))


(defn cljs-repl []
  (do (figwheel-sidecar.repl-api/stop-figwheel!)
      (figwheel-sidecar.repl-api/start-figwheel! "dev" "devcards")
      (figwheel-sidecar.repl-api/cljs-repl)))

(defn migrate! [] (migrate (ragtime/config)))
(defn rollback! [] (rollback (ragtime/config)))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src/clj" "test/clj")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(reloaded.repl/set-init! #(tictag.system/system config))

(defn dev-user [] (db/get-user (:db system) "test"))

(def my-user-id (-> (select :id)
                    (from :users)
                    (where [:= :username "test"])
                    (limit 1)))

(def my-beeminder-id (-> (select :beeminder.id)
                         (from :beeminder)
                         (join :users [:= :users.id my-user-id])))

(defn make-dev-user []
  (j/with-db-transaction [tx (-> system :db :db)]
    (j/execute! tx
                (-> (insert-into :users)
                    (values [{:username "test"
                              :email    "tictag-test@agh.io"
                              :pass     (db/hashp "test")
                              :tz       "America/Los_Angeles"}])
                    (upsert (-> (on-conflict :username)
                                (do-nothing)))
                    sql/format))
    (let [{:keys [encrypted iv]} (crypto/encrypt (env :dev-beeminder-token)
                                                 (-> system :db :crypto-key))]
      (j/execute! tx
                  (-> (insert-into :beeminder)
                      (values [{:user_id         my-user-id
                                :username        "tictagtest"
                                :encrypted_token encrypted
                                :encryption_iv   iv
                                :is_enabled      true}])
                      (upsert (-> (on-conflict :user_id)
                                  (do-update-set :encrypted_token :encryption_iv)))
                      sql/format)))))

(def p (insta/parser (clojure.java.io/resource "parser.bnf")))
(defn e [v] (evaluate {:db (:db system) :user (dev-user)} v))

