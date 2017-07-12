(ns tictag.db
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [clj-time.jdbc]
            [clj-time.coerce :as coerce]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [tictagapi.core :as tagtime]
            [clojure.java.jdbc :as j]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [honeysql.core :as sql]
            [honeysql.helpers :refer [select
                                      update
                                      order-by
                                      where
                                      merge-where
                                      from
                                      limit
                                      insert-into
                                      values
                                      sset
                                      delete-from
                                      left-join
                                      group
                                      join]]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [tictag.crypto :as crypto]
            [buddy.hashers :refer [check]]
            [hikari-cp.core :as hikari]
            [tictag.utils :as utils]))

(defn decrypt [db t iv]
  (crypto/decrypt t (:crypto-key db) iv))

(defn with-last-ping
  "This is SUPER hacky and only works for the `db/last-pings` fn."
  [db {id :id} ts]
  (timbre/trace "Using thread-ts" ts)
  (if-let [[ping] (j/query (:db db)
                           (-> (select :ping-ts)
                               (from :ping-threads)
                               (where [:= :ping-threads.slack-ts ts])
                               sql/format))]
    (assoc db :_last-ping (:ping-ts ping))
    db))

(defn beeminder-id [user-id]
  (-> (select :beeminder.id)
      (from :beeminder)
      (where [:= :beeminder.user-id user-id])))

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
    (timbre/debug "Starting database pool")
    (assoc component
           :db {:datasource (hikari/make-datasource (datasource-options db-spec))
                :crypto-key (:crypto-key db-spec)}))
  (stop [component]
    (when-let [ds (get-in component [:db :datasource])]
      (timbre/debug "Closing database pool.")
      (hikari/close-datasource ds))
    (dissoc component :db)))

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
       (upsert (-> (on-conflict :ts :user-id)
                   (do-nothing)))
       sql/format)))

(defn add-pending! [db time id]
  (add-afk-pings! db time)
  (j/execute! (:db db)
              (-> (insert-into :ping-ids)
                  (values [{:id id :ts time}])
                  (upsert (-> (on-conflict :id)
                              (do-update-set :ts)))
                  sql/format)))

(defn pending-timestamp [{:keys [db]} id]
  (:ts
   (first
    (j/query db
             (-> (select :ts)
                 (from :ping-ids)
                 (where [:= :id id])
                 (limit 1)
                 sql/format)))))

(def ymd (f/formatter "yyyyMMdd"))
(defn local-day [local-time] (f/unparse ymd local-time))

(defn to-ping [{:keys [local_time ts tags user_id]}]
  {:tags       (set (str/split tags #" "))
   :user-id    user_id
   :local-time local_time
   :local-day  (local-day local_time)
   :timestamp  (coerce/to-long ts)})

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
       (merge-where
        (when-let [now (:_last-ping db)]
          (timbre/trace "looking for last-pings with <= ts " now)
          [:<= :ts now]))
       (order-by [:ts :desc])
       (limit count))))

(defn last-ping [db user]
  (first (last-pings db user 1)))

(defn is-ping? [{tagtime :tagtime} long-time]
  (tagtime/is-ping? tagtime long-time))

(defn ping-from-long-time [db user long-time]
  (or
   (first
    (get-pings
     (:db db)
     (-> ping-select
         (where [:= :ts (coerce/from-long long-time)] [:= :user_id (:id user)])
         (limit 1))))
   (when (is-ping? db long-time)
     {:user-id   (:id user)
      :tags      #{"afk"}
      :timestamp long-time})))

(defn pings
  "An infinite list of pings from tagtime"
  [{tagtime :tagtime}]
  (:pings tagtime))


(defn update-tags-with-slack-ts [{db :db} time c]
  (j/with-db-transaction [db db]
    (doseq [resp (remove #(nil? @%) c)]
      (j/execute! db (-> (insert-into :ping-threads)
                         (values
                          [{:slack-ts (get-in @resp [:body :ts])
                            :ping-ts  time}])
                         sql/format)))))

(defn update-tags! [{db :db} pings]
  (timbre/tracef "Updating pings: %s" (pr-str pings))
  (j/with-db-transaction [db db]
    (doseq [{:keys [tags user-id timestamp]} pings]
      (j/execute! db (-> (update :pings)
                         (sset {:tags (str/join " " tags)})
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
       (drop-while #(-> % :tags (get "afk") not))
       (take-while #(-> % :tags (get "afk" )))))

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
     (sql/format (write-beeminder-sql (:crypto-key db) user username token enabled?)))
    (assoc
     (first
      (j/query
       (:db db)
       (sql/format
        (select (beeminder-id (:id user))
                :beeminder.id :beeminder.username))))
     :token
     token)))

(defn slack-record [crypto-key user username token dm-id slack-user-id]
  (let [{:keys [encrypted iv]} (crypto/encrypt token crypto-key)]
    {:user_id                    (:id user)
     :username                   username
     :encrypted_bot_access_token encrypted
     :encryption_iv              iv
     :dm-id                      dm-id
     :slack_user_id              slack-user-id}))

(defn write-slack-sql [crypto-key user username token dm-id slack-user-id]
  (-> (insert-into :slack)
      (values [(slack-record crypto-key user username token dm-id slack-user-id)])))


(defn write-slack [db user username token dm-id slack-user-id]
  (let [{:keys [encrypted iv]} (crypto/encrypt token (:crypto-key db))]
    (j/execute!
     (:db db)
     (sql/format
      (write-slack-sql
       (:crypto-key db)
       user
       username
       token
       dm-id
       slack-user-id)))))

(defn delete-slack [db user-id]
  (j/execute! (:db db) (-> (delete-from :slack)
                           (where [:= :user-id user-id])
                           sql/format)))

(defn delete-beeminder [db user-id]
  (j/execute! (:db db) (-> (delete-from :beeminder)
                           (where [:= :user-id user-id])
                           sql/format)))

(defn enable-beeminder [db user-id enable?]
  (j/execute! (:db db)
              (-> (update :beeminder)
                  (sset {:is-enabled enable?})
                  (where [:= :user-id user-id])
                  sql/format)))

(defn beeminder-from-db
  [db {:as user :keys [beeminder_username
                       beeminder_encrypted_token
                       beeminder_encryption_iv
                       beeminder_is_enabled
                       id
                       beeminder_id]}]
  (when (and user beeminder_encrypted_token beeminder_encryption_iv)
    {:id       beeminder_id
     :username beeminder_username
     :user-id  id
     :enabled? beeminder_is_enabled
     :token    (decrypt
                db
                beeminder_encrypted_token
                beeminder_encryption_iv)}))

(defn slack-from-db [db {:as user :keys [slack_username
                                         slack_encrypted_bot_access_token
                                         slack_encryption_iv
                                         slack_channel_id
                                         slack_channel_name
                                         slack_dm_id
                                         slack_id
                                         slack_use_channel
                                         slack_use_dm
                                         id]}]
  (when (and user slack_encrypted_bot_access_token slack_encryption_iv)
    {:username         slack_username
     :id               slack_id
     :user-id          id
     :dm?              slack_use_dm
     :dm-id            slack_dm_id
     :channel?         slack_use_channel
     :channel-id       slack_channel_id
     :channel-name     slack_channel_name
     :bot-access-token (decrypt
                        db
                        slack_encrypted_bot_access_token
                        slack_encryption_iv)}))

(defn macros [db id]
  (map #(utils/with-ns % "macro")
       (j/query
        (:db db)
        (-> (select :id :expands-from :expands-to :user-id)
            (from :macroexpansions)
            (where [:= :user-id id])
            sql/format))))

(defn with-macros [db user]
  (when user
    (assoc user :macros (macros db (:id user)))))

(defn with-beeminder [db user]
  (when user (assoc user :beeminder (beeminder-from-db db user))))

(defn with-slack [db user]
  (when user (assoc user :slack (slack-from-db db user))))

(defn to-user [db user]
  (when user
    (->> user
         (with-macros db)
         (with-slack db)
         (with-beeminder db))))

(def user-query
  (-> (select :users.*

              [:slack.encrypted_bot_access_token :slack_encrypted_bot_access_token]
              [:slack.encryption_iv :slack_encryption_iv]
              [:slack.slack_user_id :slack_user_id]
              [:slack.username :slack_username]
              [:slack.channel_id :slack_channel_id]
              [:slack.dm_id :slack_dm_id]
              [:slack.use_dm :slack_use_dm]
              [:slack.use_channel :slack_use_channel]
              [:slack.id :slack_id]
              [:slack.channel_name :slack_channel_name]

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
  (when slack-id
    (to-user
     db
     (first
      (j/query (:db db)
               (sql/format
                (where user-query [:= :slack.slack_user_id slack-id])))))))

(defn update-timezone [db user-id tz]
  (j/execute!
   (:db db)
   (-> (update :users)
       (sset {:tz tz})
       (where [:= user-id :users.id])
       sql/format)))

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

(defn get-all-slack-channels [db]
  (->> (j/query
        (:db db)
        (-> (select :slack.channel-id
                    :slack.encrypted-bot-access-token
                    :slack.encryption-iv
                    :users.tz)
            (from :slack)
            (left-join :users [:= :slack.user_id :users.id])
            (where [:= :use-channel true]
                   [:not= :channel-id nil])
            (order-by :slack.channel-id)
            sql/format))
       (partition-by :channel_id)
       (map first)
       (map (fn [{token   :encrypted_bot_access_token
                  iv      :encryption_iv
                  channel :channel_id
                  tz      :tz}]
              {:token   (decrypt db token iv)
               :channel channel
               :tz      tz}))))

(defn get-all-slack-dms [db]
  (->> (j/query
        (:db db)
        (-> (select :slack.dm-id
                    :slack.encrypted-bot-access-token
                    :slack.encryption-iv
                    :users.tz)
            (from :slack)
            (left-join :users [:= :slack.user_id :users.id])
            (where [:= :use-dm true])
            (order-by :slack.dm-id)
            sql/format))
       (partition-by :dm_id)
       (map first)
       (map (fn [{token   :encrypted_bot_access_token
                  iv      :encryption_iv
                  channel :dm_id
                  tz      :tz}]
              {:token   (decrypt db token iv)
               :channel channel
               :tz      tz}))))

(defn get-all-slacks [db]
  (concat
   (get-all-slack-dms db)
   (get-all-slack-channels db)))

(defn authenticated-user [db username password]
  (let [user (get-user db username)]
    (when (check password (:pass user))
      user)))

(defn get-goals-raw [db beeminder-user]
  (map
   (fn [{:keys [goal tags id]}]
     {:goal/name goal
      :goal/tags tags
      :goal/id id})
   (j/query
    (:db db)
    (-> (select :goal :tags :id)
        (from :beeminder_goals)
        (where [:= (:id beeminder-user) :beeminder_id])
        sql/format))))

(defn get-goal-raw [db user-id goal]
  (first
   (j/query
    (:db db)
    (-> (select :id)
        (from :beeminder-goals)
        (where [:= :id (:goal/id goal)]
               [:= :beeminder-id (-> (select :beeminder.id)
                                     (from :beeminder)
                                     (where [:= :beeminder.user-id user-id]))])
        sql/format))))

(defn get-goals [db beeminder-user]
  (map
   #(clojure.core/update % :goal/tags edn/read-string)
   (get-goals-raw db beeminder-user)))

(defn add-goal [db user-id goal]
  (j/execute!
   (:db db)
   (-> (insert-into [[:beeminder-goals [:goal :tags :beeminder-id]]
                     (select (beeminder-id user-id)
                             (:goal/name goal)
                             (:goal/tags goal)
                             :beeminder.id)])
       sql/format))
  (first
   (j/query
    (:db db)
    (-> (select :id)
        (from :beeminder-goals)
        (where [:= :goal (:goal/name goal)]
               [:= :tags (:goal/tags goal)]
               [:= :beeminder-id (beeminder-id user-id)])
        sql/format))))


(defn update-goal [db user-id goal]
  (j/execute!
   (:db db)
   (-> (update :beeminder-goals)
       (sset {:goal (:goal/name goal)
              :tags (:goal/tags goal)})
       (where [:= (:goal/id goal) :id]
              [:= :beeminder-id (beeminder-id user-id)])
       sql/format)))


(defn delete-goal [db user-id goal-id]
  (j/execute!
   (:db db)
   (-> (delete-from :beeminder-goals)
       (where [:= :id goal-id]
              [:= :beeminder-id (beeminder-id user-id)])
       sql/format)))

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

(defn insert-tagtime-data [db data]
  (j/execute!
   (:db db)
   (-> (insert-into :pings)
       (values data)
       (upsert (-> (on-conflict :ts :user-id)
                   (do-update-set :tags :tz_offset)))
       sql/format)))

(defn update-slack! [db user-id vs]
  (timbre/trace user-id vs)
  (j/execute!
   (:db db)
   (-> (update :slack)
       (sset vs)
       (where [:= :user-id user-id])
       sql/format)))

(def allowed-keys
  {:macro [:macro/expands-from :macro/expands-to]})

(def to-table
  {:macro :macroexpansions})

(defn to-type [type entity]
  (select-keys entity (allowed-keys type)))

(defn create! [db user-id type entity]
  (utils/with-ns
    (j/db-do-prepared-return-keys
     (:db db)
     (-> (insert-into (to-table type))
         (values [(assoc (to-type type entity) :user-id user-id)])
         sql/format))
    "macro"))

(defn update! [db user-id id type entity]
  (utils/with-ns
    (j/db-do-prepared-return-keys
     (:db db)
     (-> (update (to-table type))
         (sset (to-type type entity))
         (where [:= :user-id user-id]
                [:= :id id])
         sql/format))
    "macro"))

(defn delete! [db user-id id type entity]
  (j/execute!
   (:db db)
   (-> (delete-from (to-table type))
       (where [:= :user-id user-id]
              [:= :id id])
       sql/format)))

