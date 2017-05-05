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
            [tictag.tagtime :as tt]
            [ragtime.repl :refer [migrate rollback]]
            [honeysql.core :as sql]
            [honeysql.helpers :refer :all]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all]
            [clojure.java.jdbc :as j]
            [tictag.crypto :as crypto]
            [environ.core :refer [env]]
            [figwheel-sidecar.repl-api]))

(defn cljs-lein-repl []
  (do (figwheel-sidecar.repl-api/start-figwheel!)
      (figwheel-sidecar.repl-api/cljs-repl)))

(defn migrate! [] (migrate ragtime/config))
(defn rollback! [] (rollback ragtime/config))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src/clj" "test/clj")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(reloaded.repl/set-init! #(tictag.system/system config))

(defn dev-user [] (db/get-user (:db system) "TEST_USER"))

(def my-user-id (-> (select :id)
                    (from :users)
                    (where [:= :username "TEST_USER"])
                    (limit 1)))

(def my-beeminder-id (-> (select :beeminder.id)
                         (from :beeminder)
                         (join :users [:= :users.id my-user-id])))

(defn make-dev-user []
  (j/with-db-transaction [tx (-> system :db :db)]
    (j/execute! tx
                (-> (insert-into :users)
                    (values [{:username "TEST_USER"
                              :email    "tictag-test@agh.io"
                              :pass     (db/hashp "foobar")
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
                      sql/format)))
    (let [{:keys [encrypted iv]} (crypto/encrypt
                                  (env :dev-slack-bot-token)
                                  (-> system :db :crypto-key))]
      (j/execute! tx
                  (-> (insert-into :slack)
                      (values [{:user_id                    my-user-id
                                :username                   (env :dev-slack-username)
                                :encrypted_bot_access_token encrypted
                                :encryption_iv              iv
                                :channel_id                 (env :dev-slack-channel-id)
                                :slack_user_id              (env :dev-slack-user-id)}])
                      (upsert (-> (on-conflict :user_id)
                                  (do-update-set :encrypted_bot_access_token
                                                 :encryption_iv)))
                      sql/format)))
    (j/execute! tx
                (-> (insert-into :beeminder_goals)
                    (values [{:beeminder_id my-beeminder-id
                              :goal         "test"
                              :tags         (pr-str [:and :this :that])}])
                    (upsert (-> (on-conflict :beeminder_id :goal)
                                (do-nothing)))
                    sql/format))))
