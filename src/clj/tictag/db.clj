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

(declare replace-key)

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
              (-> (h/update :beeminder)
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

(defmulti client-trans (fn [a b] a))
(defmethod client-trans :default [_ v] v)

(defn format-date [d]
  (f/unparse utils/wtf d))

(defn days-since-epoch [date]
  (t/in-days (t/interval (t/epoch) date)))

(defmethod client-trans :ping/by-id
  [_ v]
  (let [local-time (:ping/local-time v)
        [y m d]    ((juxt t/year t/month t/day) local-time)]
    (-> v
        (assoc :ping/days-since-epoch (t/in-days (t/interval (t/epoch) (:ping/local-time v))))
        (assoc :ping/seconds-since-midnight (t/in-seconds (t/interval (t/date-time y m d) local-time)))
        (assoc :ping/tag-set (set (str/split (:ping/tags v) #" "))))))

(defmethod client-trans :beeminder/by-id
  [_ v]
  (-> v
      (replace-key [:beeminder/is-enabled :beeminder/enabled?])
      (select-keys [:beeminder/id :beeminder/user-id :beeminder/username :beeminder/enabled?])))

(defmethod client-trans :slack/by-id
  [_ v]
  (-> v
      (replace-key [:slack/use-dm :slack/dm?])
      (replace-key [:slack/use-channel :slack/channel?])
      (select-keys [:slack/id
                    :slack/user-id
                    :slack/username
                    :slack/channel-id
                    :slack/channel?
                    :slack/channel-name
                    :slack/dm?
                    :slack/dm-id
                    :slack/slack-user-id])))

(defn client-converter [type]
  (fn [things]
    (reduce
     (fn [accu v]
       (update accu type assoc (:id v) (client-trans type (utils/with-ns v (namespace type)))))
     {}
     things)))

(def beeminder-client-converter (client-converter :beeminder/by-id))

(defn beeminder [db id]
  (j/query
   (:db db)
   (-> (select :user-id :id :username :is-enabled)
       (from :beeminder)
       (where [:= :user-id id])
       (limit 1)
       sql/format)))

(defn goals [db id]
  (j/query
   (:db db)
   (-> (select :user-id :id :goal :tags)
       (from :beeminder-goals)
       (where [:= :user-id id])
       sql/format)))

(defn beeminder-client [db id]
  (beeminder-client-converter (beeminder db id)))

(def goal-client-converter (client-converter :goal/by-id))

(defn goal-client [db id]
  (goal-client-converter (goals db id)))

(def macro-client-converter (client-converter :macro/by-id))

(defn macros [db id]
  (j/query
   (:db db)
   (-> (select :id :expands-from :expands-to :user-id)
       (from :macroexpansions)
       (where [:= :user-id id])
       sql/format)))

(defn macro-client [db id]
  (macro-client-converter (macros db id)))

(def slack-client-converter (client-converter :slack/by-id))

(defn slack [db id]
  (j/query
   (:db db)
   (-> (select :id :user-id :slack-user-id :username :channel-id :channel-name :dm-id :use-dm :use-channel)
       (from :slack)
       (where [:= :user-id id])
       sql/format)))

(defn slack-client [db id]
  (slack-client-converter (slack db id)))

(defn ping [db id]
  (j/query
   (:db db)
   (-> (select :id :ts :tags :user-id [(sql/call :+ :tz_offset :ts) :local-time])
       (from :pings)
       (where [:= :user-id id])
       sql/format)))

(def ping-client-converter (client-converter :ping/by-id))

(defn ping-client [db id]
  (let [db     (ping-client-converter (ping db id))
        pings  (vals (:ping/by-id db))
        sorted (time (sort (compare-by :ping/ts descending) pings))]
    (assoc db
           :ping/sorted-ids
           (map :ping/id sorted))))

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
   (-> (h/update :users)
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

(defn get-goals [db beeminder-user]
  (map
   #(clojure.core/update % :goal/tags edn/read-string)
   (get-goals-raw db beeminder-user)))

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
   (-> (h/update :slack)
       (sset vs)
       (where [:= :user-id user-id])
       sql/format)))

(defn encrypt-beeminder-token [{:keys [entity db]}]
  (when-let [token (:beeminder/token entity)]
    (let [{:keys [encrypted iv]} (encrypt db token)]
      {:entity (-> entity
                   (assoc :beeminder/encrypted-token encrypted
                          :beeminder/encryption-iv iv)
                   (dissoc :beeminder/token))})))

(defn check-beeminder-token [{:keys [entity errors path]}]
  (when-let [token (:beeminder/token entity)]
    (if-let [bm-user (let [resp (-> (http/request
                                     {:url          "https://www.beeminder.com/api/v1/users/me.json"
                                      :method       :get
                                      :query-params {:auth_token token}})
                                    (deref))]
                       (if (= (:status resp) 200)
                         (cheshire/parse-string (:body resp) true)
                         nil))]
      {:entity (assoc entity :beeminder/username (:username bm-user))}
      {:errors (update-in errors (conj path :beeminder/token) conj "invalid Beeminder token!")
       :entity nil})))

(defn add-user-id [{:keys [entity user-id action]}]
  (when (= action :insert)
    {:entity (when entity (assoc entity :user-id user-id))}))

(defn execute-interceptor [ctx interceptor]
  (merge ctx (interceptor ctx)))

(defn execute [ctx]
  (loop [ctx ctx]
    (let [queue (:queue ctx)]
      (if (empty? queue)
        ctx
        (let [interceptor (peek queue)]
          (recur (-> ctx
                     (assoc :queue (pop queue))
                     (execute-interceptor interceptor))))))))

(def write-keys
  {:beeminder [:beeminder/token :beeminder/enabled?]
   :macro     [:macro/expands-from :macro/expands-to]
   :goal      [:goal/goal :goal/tags]
   :slack     [:slack/channel-id :slack/channel? :slack/channel-name :slack/dm?]})

(defn filter-keys [keys]
  (fn [{:keys [entity]}]
    {:entity (select-keys entity keys)}))

(defn replace-key [entity [old-key new-key]]
  (if (contains? entity old-key)
    (-> entity (assoc new-key (get entity old-key)) (dissoc old-key))
    entity))

(defn replace-keys* [v keys]
  (reduce replace-key v keys))

(defn replace-keys [keys]
  (fn [{:keys [entity]}]
    {:entity (reduce replace-key entity keys)}))

(defn lookup-channel-name [{:keys [entity db user-id slack errors path] :as ctx}]
  (when-let [channel-name (:slack/channel-name entity)]
    (if-let [id (slack/channel-id
                 (:bot-access-token
                  (:slack (get-user-by-id db user-id)))
                 channel-name)]
      {:entity (assoc entity :slack/channel-id id)}
      {:errors (update-in errors (conj path :slack/channel-name) conj "couldn't find Slack channel!")
       :entity nil})))

(defn table [t]
  #(assoc % :table t))

(def select-for
  {:ping [:id
          :ts
          :tags
          :user-id
          [(sql/call :+ :tz-offset :ts) :local-time]]})

(defn set-sql [{:keys [user-id entity where id path table action type] :as ctx}]
  (case action
    :delete {:sql {:delete-from table :where where}}
    :insert {:sql {:insert-into table :values [entity] :returning (or (select-for type)
                                                                      [:*])}}
    :update {:sql {:update table :set entity :where where :returning (or (select-for type)
                                                                         [:*])}}))

(defn set-action [{:keys [entity id user-id]}]
  (cond
    (nil? entity) {:action   :delete
                   :where [:and [:= :user-id user-id] [:= :id id]]}
    (= id :temp)  {:action :insert}
    :else         {:action   :update
                   :where [:and [:= :user-id user-id] [:= :id id]]}))

(defn set-sql-params [{:keys [sql]}]
  (when sql {:sql-params (sql/format sql)}))

(defn execute! [{:keys [db sql-params errors id path] :as ctx}]
  (cond
    errors ctx

    (not sql-params) (update ctx :errors conj :invalid-data)

    :else
    (try (merge ctx {:result (j/db-do-prepared-return-keys (:db db) sql-params)})
         (catch org.postgresql.util.PSQLException e
           (timbre/debug sql-params e)
           (update ctx :errors (fn [errs]
                                 (assoc-in errors (conj path :error) "an unknown error occurred")))))))


(defn ->kebab+ns [ns]
  (fn [{:keys [result]}]
    {:result (utils/with-ns result (name ns))}))

(defn debug [entity]
  (timbre/debug (dissoc entity :db))
  entity)

(defn select-result-keys [ks]
  (fn [v]
    (update v :result select-keys ks)))

(defn replace-result-keys [ks]
  (fn [v]
    (update v :result replace-keys* ks)))

(defn apply-client-converter [converter]
  (fn [{:keys [result]}]
    (timbre/debug result)
    {:result (timbre/spy (converter [result]))}))

(def beeminder-read
  [(apply-client-converter beeminder-client-converter)])

(def macro-read
  [(apply-client-converter macro-client-converter)])

(def ping-read
  [(apply-client-converter ping-client-converter)])

(def slack-read
  [(apply-client-converter slack-client-converter)])

(def goal-read
  [(apply-client-converter goal-client-converter)])

(def apply-sql [add-user-id set-sql set-sql-params execute!])

(def beeminder-incoming-interceptors
  [(table :beeminder)
   set-action
   (filter-keys [:beeminder/token :beeminder/enabled?])
   check-beeminder-token
   encrypt-beeminder-token
   (replace-keys {:beeminder/enabled? :beeminder/is-enabled})
   apply-sql
   beeminder-read])

(def macro-incoming-interceptors
  [(table :macroexpansions)
   set-action
   (filter-keys [:macro/expands-from :macro/expands-to])
   apply-sql
   macro-read])

(def goal-incoming-interceptors
  [(table :beeminder-goals)
   set-action
   (filter-keys [:goal/goal :goal/tags])
   apply-sql
   goal-read])

(def slack-incoming-interceptors
  [(table :slack)
   set-action
   (filter-keys [:slack/channel-name :slack/channel? :slack/dm?])
   lookup-channel-name
   (replace-keys {:slack/channel? :slack/use-channel
                  :slack/dm?      :slack/use-dm})
   apply-sql
   slack-read])

(def ping-incoming-interceptors
  [(table :pings)
   set-action
   (filter-keys [:ping/tags])
   apply-sql
   ping-read])

(defn make-new-db [db {:keys [result action path errors namespace]}]
  (cond
    (seq errors) (assoc db :db/errors errors)

    (= action :delete) (assoc-in db path nil)

    (= action :insert) (deep-merge* db result)

    :else (deep-merge* db result)))

(def incoming-interceptors
  {:macro     macro-incoming-interceptors
   :beeminder beeminder-incoming-interceptors
   :goal      goal-incoming-interceptors
   :slack     slack-incoming-interceptors
   :ping      ping-incoming-interceptors})

(defn make-queue [t]
  (when-let [is (flatten (incoming-interceptors t))]
    (into [] (reverse is))))

(defn make-contexts [db user-id diff]
  (->> diff
       (map
        (fn [[k m]]
          (map (fn [[id entity]]
                 {:path      [k id]
                  :db        db
                  :user-id   user-id
                  :selector  k
                  :namespace (namespace k)
                  :type      (keyword (namespace k))
                  :queue     (make-queue (keyword (namespace k)))
                  :id        id
                  :entity    entity})
               m)))
       (flatten)))

(defn persist!
  "In: db, user-id, and something like: {:macro/by-id {1 {:macro/expands-from \"abc\" :macro/expands-to \"123\"}}}"
  [db user-id new-db]
  (let [entities (utils/to-entities new-db)]
    (->> (make-contexts db user-id new-db)
         (map execute)
         (reduce make-new-db {}))))


