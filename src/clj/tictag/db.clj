(ns tictag.db
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [clj-time.jdbc]
            [clj-time.coerce :as coerce]
            [clj-time.format :as f]
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
            [hikari-cp.core :as hikari]
            [tictag.utils :as utils]))

(def ping-select (-> (select :ts
                             [(sql/call :+ :tz_offset :ts) :local_time]
                             :tags
                             :user_id)
                     (from :pings)))

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
      (timbre/debugf "Closing database pool: %s" ds)
      (hikari/close-datasource ds))
    (dissoc component :db)))

(defn add-pend! [rb id time]
  (into rb [[id time]]))

(defn add-pings!
  "At this point, just used for parsed TagTime data"
  [db pings]
  (j/execute!
   (:db db)
   (-> (insert-into :pings)
       (values pings)
       sql/format)))

(defn add-afk-pings! [db time]
  (j/execute!
   (:db db)
   (-> (insert-into
        [[:pings [:ts :tz_offset :tags :user_id]]
         (-> (select
              time
              (sql/call :-
                        (sql/call :timezone
                                  :users.tz
                                  time)
                        (sql/call :timezone
                                  "UTC"
                                  time))
              "afk"
              :id)
             (from :users))])
       sql/format)))

(defn add-pending! [{:keys [pends] :as db} time id]
  (add-afk-pings! db time)
  (swap! pends add-pend! id time))

(defn pending-timestamp [{:keys [pends]} id]
  (second (first (filter #(= (first %) id) @pends))))

(def ymd (f/formatter "yyyyMMdd"))
(defn local-day [local-time] (f/unparse ymd local-time))

(defn to-ping [{:keys [local_time ts tags user_id]}]
  {:tags              (set (map keyword (str/split tags #" ")))
   :user-id           user_id
   :local-time        local_time
   :local-day         (local-day local_time)
   :timestamp         (coerce/to-long ts)})

(defn get-pings [db query]
  (map to-ping (j/query db (sql/format query))))

(defn get-pings-by-user [db user]
  (get-pings db (-> ping-select
                    (where [:= :pings.user_id (:id user)]))))

(defn ping-from-id [db user id]
  (let [timestamp (pending-timestamp db id)]
    (first
     (get-pings
      (:db db)
      (-> ping-select
          (where [:= :ts timestamp]
                 [:= :user_id (:id user)])
          (limit 1))))))

(defn last-pings [db user count]
  (get-pings
   (:db db)
   (-> ping-select
       (where [:= :user_id (:id user)])
       (order-by [:ts :desc])
       (limit count))))

(defn last-ping [db user]
  (first (last-pings db user 1)))


(defn ping-from-long-time [db user long-time]
  (first
   (get-pings
    (:db db)
    (-> ping-select
        (where [:= :ts (coerce/from-long long-time)] [:= :user_id (:id user)])
        (limit 1)))))

(defn is-ping? [{tagtime :tagtime} long-time]
  (tagtime/is-ping? tagtime long-time))

(defn pings
  "An infinite list of pings from tagtime"
  [{tagtime :tagtime}]
  (:pings tagtime))

(defn update-tags! [{db :db} pings]
  (timbre/debugf "Updating pings: %s" (pr-str pings))
  (j/with-db-transaction [db db]
    (doseq [{:keys [tags user-id timestamp]} pings]
      (j/execute! db (-> (update :pings)
                         (sset {:tags (str/join " " (map name tags))})
                         (where [:= :ts (coerce/from-long timestamp)]
                                [:= :user_id user-id])
                         sql/format)))))

(defn sleepy-pings
  "Return the most recent contiguous set of pings marked :afk in the database"
  [{db :db} user]
  (->> (-> ping-select
           (where [:= :user_id (:id user)])
           (order-by [:ts :desc])
           (limit 100))
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

(defn delete-slack [db user-id]
  (j/execute! (:db db) (-> (delete-from :slack)
                           (where [:= :user-id user-id])
                           sql/format)))

(defn delete-beeminder [db user-id]
  (j/execute! (:db db) (-> (delete-from :beeminder)
                           (where [:= :user-id user-id])
                           sql/format)))

(defn beeminder-from-db
  [db {:as user :keys [beeminder_username
                       beeminder_encrypted_token
                       beeminder_encryption_iv
                       beeminder_is_enabled
                       beeminder_id]}]
  (when (and user beeminder_encrypted_token beeminder_encryption_iv)
    {:id       beeminder_id
     :username beeminder_username
     :enabled? beeminder_is_enabled
     :token    (crypto/decrypt
                beeminder_encrypted_token
                (:crypto-key db)
                beeminder_encryption_iv)}))

(defn slack-from-db [db {:as user :keys [slack_username slack_encrypted_bot_access_token slack_encryption_iv slack_channel_id]}]
  (when (and user slack_encrypted_bot_access_token slack_encryption_iv)
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
      (left-join :slack [:= :slack.user_id :users.id]
                 :beeminder [:= :beeminder.user_id :users.id])))

(defn get-user-from-slack-id [db slack-id]
  (to-user
   db
   (first
    (j/query (:db db)
             (sql/format
              (where user-query [:= :slack.slack_user_id slack-id]))))))

(defn write-user [db {:keys [username password email tz] :as user}]
  (j/execute!
   (:db db)
   (-> (insert-into :users)
       (values [{:username username :email email :pass (hashp password) :tz tz}])
       sql/format)))

(defn get-user-by-id [db id]
  (to-user db (first (j/query (:db db) (sql/format (where user-query [:= :users.id id]))))))

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
    (-> (select :goal :tags :id)
        (from :beeminder_goals)
        (where [:= (:id beeminder-user) :beeminder_id])
        sql/format))))

(defn test-query! [db]
  (try (j/query (:db db) (sql/format (select 1)))
       (catch Exception e nil)))

(defn get-pings-by-user-id [db id]
  (get-pings db (-> ping-select
                    (where [:= :pings.user_id id]))))

(defn timezones [db]
  (j/query
   (:db db)
   (-> (select :name)
       (from :pg-timezone-names)
       sql/format)))
