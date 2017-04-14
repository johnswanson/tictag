(ns tictag.db
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [tictagapi.core :as tagtime]
            [clojure.java.jdbc :as j]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [honeysql.core :as sql]
            [honeysql.helpers :refer :all]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all]
            [tictag.crypto :as crypto]
            [buddy.hashers :refer [check]]
            [hikari-cp.core :as hikari]))

(defn datasource-options [{:keys [dbtype dbname host user password]}]
  {:pool-name   "db-pool"
   :adapter     dbtype
   :username    user
   :password    password
   :server-name host})

(def hashp #(buddy.hashers/derive % {:algorithm :bcrypt+blake2b-512}))

(defrecord Database [db-spec tagtime]
  component/Lifecycle
  (start [component]
    (timbre/debugf "Starting database, config: %s" (pr-str db-spec))
    (assoc component
           :db {:datasource (hikari/make-datasource (datasource-options db-spec))
                :crypto-key (:crypto-key db-spec)}
           :pends (atom (ring-buffer 16))))
  (stop [component]
    (when-let [ds (get-in component [:db :datasource])]
      (hikari/close-datasource ds))
    (dissoc component :db)))

(defn add-pend! [rb id long-time]
  (into rb [[id long-time]]))

(defn add-afk-pings! [db ts]
  (j/execute!
   (:db db)
   ["INSERT INTO pings (ts, tags, local_time, user_id) SELECT ?, 'afk', timezone(tz, to_timestamp(? / 1000)), id FROM users" ts ts]))

(defn add-pending! [{:keys [pends] :as db} long-time id]
  (add-afk-pings! db long-time)
  (swap! pends add-pend! id long-time))

(defn pending-timestamp [{:keys [pends]} id]
  (second (first (filter #(= (first %) id) @pends))))

(defn local-day [local-time] (str/replace (subs local-time 0 10) #"-" ""))

(defn to-ping [{:keys [local_time ts tags calendar_event_id user_id]}]
  {:tags              (set (map keyword (str/split tags #" ")))
   :user-id           user_id
   :local-time        local_time
   :local-day         (local-day local_time)
   :timestamp         ts
   :calendar-event-id calendar_event_id})

(defn get-pings [db query]
  (map to-ping (j/query db query)))

(defn get-pings-by-user [db user]
  (get-pings db (-> (select :*)
                    (from :pings)
                    (where [:= :pings.user_id (:id user)])
                    sql/format)))

(defn ping-from-id [db user id]
  (let [timestamp (pending-timestamp db id)]
    (first
     (get-pings
      (:db db)
      ["select * from pings where ts=? and user_id=? limit 1"
       timestamp
       (:id user)]))))

(defn last-ping [db user]
  (first
   (get-pings
    (:db db)
    ["select * from pings where user_id=? order by ts desc limit 1"
     (:id user)])))

(defn ping-from-long-time [db user long-time]
  (first
   (get-pings
    (:db db)
    ["select * from pings where ts=? and user_id? limit 1"
     long-time
     (:id user)])))

(defn is-ping? [{tagtime :tagtime} long-time]
  (tagtime/is-ping? tagtime long-time))

(defn pings
  "An infinite list of pings from tagtime"
  [{tagtime :tagtime}]
  (:pings tagtime))

(defn to-db-ping [{:keys [user-id tags timestamp local-time calendar-event-id]}]
  (let [ping {:user_id           user-id
              :tags              (str/join " " tags)
              :ts                timestamp
              :calendar_event_id calendar-event-id}]
    (if local-time
      (assoc ping :local_time local-time)
      ping)))

(defn update-tags! [{db :db} pings]
  (timbre/debugf "Updating pings: %s" (pr-str pings))
  (j/execute! db
              (-> (insert-into :pings)
                  (values (map to-db-ping pings))
                  (upsert (-> (on-conflict :ts :user_id)
                              (do-update-set :tags :local_time)))
                  sql/format)))

(defn sleepy-pings
  "Return the most recent contiguous set of pings marked :afk in the database"
  [{db :db} user]
  (->> ["select * from pings where user_id = ? order by ts desc limit 100" (:id user)]
       (get-pings db)
       (drop-while (comp not :afk :tags))
       (take-while (comp :afk :tags))))

(defn sleep [ping]
  (assoc ping :tags #{"sleep"}))

(defn make-pings-sleepy! [db pings]
  (update-tags! db (map sleep pings)))

(defn beeminder-record [crypto-key user username token enabled?]
  (let [{:keys [encrypted iv]} (crypto/encrypt token crypto-key)]
    {:user_id         (:id user)
     :username        username
     :encrypted_token encrypted
     :encryption_iv   iv
     :is_enabled      enabled?}))

(defn write-beeminder-sql [crypto-key user username token enabled?]
  (-> (insert-into :beeminder)
      (values [(beeminder-record crypto-key user username token enabled?)])))

(defn write-beeminder [db user username token enabled?]
  (let [{:keys [encrypted iv]} (crypto/encrypt token (:crypto-key db))]
    (j/execute!
     (:db db)
     (sql/format (write-beeminder-sql (:crypto-key db) user username token enabled?)))))

(defn slack-record [crypto-key user username token channel-id slack-user-id]
  (let [{:keys [encrypted iv]} (crypto/encrypt token crypto-key)]
    {:user_id                    (:id user)
     :username                   username
     :encrypted_bot_access_token encrypted
     :encryption_iv              iv
     :channel_id                 channel-id
     :slack_user_id              slack-user-id}))

(defn write-slack-sql [crypto-key user username token channel-id slack-user-id]
  (-> (insert-into :slack)
      (values [(slack-record crypto-key user username token channel-id slack-user-id)])))


(defn write-slack [db user username token channel-id slack-user-id]
  (let [{:keys [encrypted iv]} (crypto/encrypt token (:crypto-key db))]
    (j/execute!
     (:db db)
     (sql/format
      (write-slack-sql
       (:crypto-key db)
       user
       username
       token
       channel-id
       slack-user-id)))))

(defn beeminder-from-db
  [db {:as user :keys [beeminder_username
                       beeminder_encrypted_token
                       beeminder_encryption_iv
                       beeminder_is_enabled
                       beeminder_id]}]
  (when user
    {:id       beeminder_id
     :username beeminder_username
     :enabled? beeminder_is_enabled
     :token    (crypto/decrypt
                beeminder_encrypted_token
                (:crypto-key db)
                beeminder_encryption_iv)}))

(defn slack-from-db [db {:as user :keys [slack_username slack_encrypted_bot_access_token slack_encryption_iv slack_channel_id]}]
  (when user
    {:username         slack_username
     :channel-id       slack_channel_id
     :bot-access-token (crypto/decrypt
                        slack_encrypted_bot_access_token
                        (:crypto-key db)
                        slack_encryption_iv)}))

(defn to-user [db user]
  (when user
    (-> user
        (assoc :slack (slack-from-db db user))
        (assoc :beeminder (beeminder-from-db db user)))))

(def user-query
  (-> (select :users.*

              [:slack.encrypted_bot_access_token :slack_encrypted_bot_access_token]
              [:slack.encryption_iv :slack_encryption_iv]
              [:slack.slack_user_id :slack_user_id]
              [:slack.username :slack_username]
              [:slack.channel_id :slack_channel_id]

              [:beeminder.id :beeminder_id]
              [:beeminder.user_id :beeminder_user_id]
              [:beeminder.username :beeminder_username]
              [:beeminder.encrypted_token :beeminder_encrypted_token]
              [:beeminder.encryption_iv :beeminder_encryption_iv]
              [:beeminder.is_enabled :beeminder_is_enabled])
      (from :users)
      (join :slack [:= :slack.user_id :users.id]
            :beeminder [:= :beeminder.user_id :users.id])))

(defn get-user-from-slack-id [db slack-id]
  (to-user
   db
   (first
    (j/query (:db db)
             (sql/format
              (where user-query [:= :slack.slack_user_id slack-id]))))))

(defn write-user [db username email password]
  (j/execute!
   (:db db)
   [(str/join " "
              ["INSERT INTO users"
               "(username, email, pass)"
               "VALUES"
               "(?, ?, ?)"])
    username
    email
    (hashp password)]))

(defn get-user [db username]
  (to-user db (first (j/query (:db db) (sql/format (where user-query [:= :users.username username]))))))

(defn get-all-users [db]
  (map #(to-user db %) (j/query (:db db) (sql/format user-query))))

(defn authenticated-user [db username password]
  (let [user (get-user db username)]
    (when (check password (:pass user))
      user)))

(defn get-goals [db beeminder-user]
  (map
   #(clojure.core/update % :tags edn/read-string)
   (j/query
    (:db db)
    (-> (select :goal :tags)
        (from :beeminder_goals)
        (where [:= (:id beeminder-user) :beeminder_id])
        sql/format))))
