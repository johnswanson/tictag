(ns tictag.db
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [cheshire.core :as cheshire]
            [org.httpkit.client :as http]
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
                                      join]
             :as h]
            [honeysql-postgres.format :refer :all]
            [honeysql-postgres.helpers :refer :all :exclude [partition-by]]
            [tictag.crypto :as crypto]
            [buddy.hashers :refer [check]]
            [hikari-cp.core :as hikari]
            [tictag.utils :as utils :refer [deep-merge* compare-by descending]]
            [tictag.slack :as slack]))

(declare replace-key replace-keys*)

(defn is-ping? [{tagtime :tagtime} long-time]
  (tagtime/is-ping? tagtime long-time))

(defn encrypt [db t]
  (when t
    (crypto/encrypt t (:crypto-key db))))

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
    (assoc db :_last-ping (:ping_ts ping))
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

(defn get-pings* [db query]
  (map to-ping (j/query db (sql/format query))))

(defn get-pings-by-user [db user]
  (get-pings* db (-> ping-select
                    (where [:= :pings.user_id (:id user)]))))

(defn ping-from-id [db user id]
  (let [timestamp (pending-timestamp db id)]
    (first
     (get-pings*
      (:db db)
      (-> ping-select
          (where [:= :ts timestamp]
                 [:= :user_id (:id user)])
          (limit 1))))))

(defn last-pings [db user count]
  (get-pings*
   (:db db)
   (-> ping-select
       (where [:= :user_id (:id user)])
       (merge-where
        (when-let [now (:_last-ping db)]
          [:= :ts now]))
       (order-by [:ts :desc])
       (limit count))))

(defn last-ping [db user]
  (first (last-pings db user 1)))


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
      (j/execute! db (-> (h/update :pings)
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
       (get-pings* db)
       (drop-while #(-> % :tags (get "afk") not))
       (take-while #(-> % :tags (get "afk" )))))

(defn sleep [ping]
  (assoc ping :tags #{"sleep"}))

(defn make-pings-sleepy! [db pings]
  (update-tags! db (map sleep pings)))

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

(defn all-pings
  "An infinite list of pings from tagtime"
  [{tagtime :tagtime}]
  (:pings tagtime))

(defn columns-for [t]
  (case t
    :ping [:id :ts :tags :user-id [(sql/call :+ :tz-offset :ts) :local-time]]
    [:*]))

(defn table-for [t]
  (case t
    :macro :macroexpansions
    :ping  :pings
    :goal  :beeminder-goals
    :slack :slack
    :user  :users
    t))

(defn where-for [t uid id]
  (case t
    :user [:= :id uid]

    [:and
     [:= :user-id uid]
     [:= :id id]]))

(defn with-ns [t v]
  (when-let [v (utils/with-ns v (name t))]
    v))

(defn get-one
  ([type {db :db} where]
   (with-ns
    type
    (first
     (j/query
      db
      (sql/format
       {:select (columns-for type)
        :from [(table-for type)]
        :where where})))))
  ([type db uid id]
   (get-one type db (where-for type uid id))))

(defn get-by-id [type {db :db} uid id]
  (with-ns
   type
   (first
    (j/query
     db
     (sql/format
      {:select (columns-for type)
       :from   [(table-for type)]
       :where  (where-for type uid id)})))))

(def sql-overrides {:ping {:order-by [[:ts :desc]]}})

(defn get-by-owner-id [type {db :db} uid]
  (map (partial with-ns type)
       (j/query
        db
        (sql/format
         (merge
          {:select (columns-for type)
           :from [(table-for type)]
           :where [:= :user-id uid]}
          (sql-overrides type))))))

(defn update-entity
  ([type db uid id v]
   (update-entity type db (where-for type uid id) v))
  ([type {db :db} where v]
   (with-ns
    type
    (j/db-do-prepared-return-keys
     db
     (sql/format
      {:update    (table-for type)
       :set       v
       :where     where
       :returning (columns-for type)})))))

(defn create [type {db :db} uid v]
  (with-ns
   type
   (j/db-do-prepared-return-keys
    db
    (sql/format
     {:insert-into (table-for type)
      :values      [(assoc v :user-id uid)]
      :returning   (columns-for type)}))))

(defn delete
  ([type {db :db} where]
   (j/execute!
    db
    (sql/format
     {:delete-from (table-for type)
      :where       where})))
  ([type db uid id]
   (delete type db (where-for type uid id))))

(defn with-beeminder [db user]
  (when user (assoc user :beeminder (beeminder-from-db db user))))

(defn with-slack [db user]
  (when user (assoc user :slack (slack-from-db db user))))

(defn to-user [db user]
  (->> user
       (with-beeminder db)
       (with-slack db)))

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

(def user-select [:id :email :username :tz])

(defn get-user-by-id [db id]
  (to-user
   db
   (first (j/query
          (:db db)
          (sql/format
           {:select user-select
            :from [:users]
            :where [:= :id id]
            :limit 1})))))

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
     {:goal/goal goal
      :goal/tags tags
      :goal/id id})
   (j/query
    (:db db)
    (-> (select :goal :tags :id)
        (from :beeminder_goals)
        (where [:= (:user-id beeminder-user) :user-id])
        sql/format))))

(defn get-goal-raw [db user-id goal]
  (first
   (j/query
    (:db db)
    (-> (select :id)
        (from :beeminder-goals)
        (where [:= :id (:goal/id goal)]
               [:= :user-id user-id])
        sql/format))))

(defn get-goals* [db beeminder-user]
  (map
   #(clojure.core/update % :goal/tags edn/read-string)
   (get-goals-raw db beeminder-user)))

(defn test-query! [db]
  (try (j/query (:db db) (sql/format (select 1)))
       (catch Exception e nil)))

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
   (-> (h/update :slack)
       (sset vs)
       (where [:= :user-id user-id])
       sql/format)))

(defn get-user-by-username-or-email [{db :db} username email]
  (first
   (j/query
    db
    (-> (select 1)
        (from :users)
        (where [:or
                [:= :username username]
                [:= :email email]])
        sql/format))))

(def get-macros (partial get-by-owner-id :macro))
(def get-macro (partial get-one :macro))
(def delete-macro (partial delete :macro))
(def update-macro (partial update-entity :macro))
(def create-macro (partial create :macro))

(def get-goals (partial get-by-owner-id :goal))
(def get-goal (partial get-one :goal))
(def delete-goal (partial delete :goal))
(def update-goal (partial update-entity :goal))
(def create-goal (partial create :goal))

(def get-beeminders (partial get-by-owner-id :beeminder))
(def get-beeminder (partial get-one :beeminder))
(def delete-beeminder (partial delete :beeminder))
(def update-beeminder (partial update-entity :beeminder))
(def create-beeminder (partial create :beeminder))

(def get-slacks (partial get-by-owner-id :slack))
(def get-slack (partial get-one :slack))
(def delete-slack (partial delete :slack))
(def update-slack (partial update-entity :slack))
(def create-slack (partial create :slack))

(def get-pings (partial get-by-owner-id :ping))
(def get-ping (partial get-one :ping))
(def delete-ping (partial delete :ping))
(def update-ping (partial update-entity :ping))
(def create-ping (partial create :ping))

(defn update-user [db uid v]
  (update-entity :user db [:= :id uid] v))
(defn get-users [db uid]
  [(get-one :user db [:= :id uid])])
(defn create-user [{db :db} v]
  (with-ns
   :user
   (j/db-do-prepared-return-keys
    db
    (sql/format
     {:insert-into :users
      :values      [v]
      :returning   [:*]}))))

