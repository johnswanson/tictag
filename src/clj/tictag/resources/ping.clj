(ns tictag.resources.ping
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :refer [id uid params processable? process replace-keys]]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.string :as str]))

(defn out [ping]
  (let [{:keys [:ping/local-time :ping/tags]} ping
        [y m d]                               ((juxt t/year t/month t/day) local-time)]
    (-> ping
        (assoc :ping/days-since-epoch (t/in-days (t/interval (t/epoch) local-time)))
        (assoc :ping/seconds-since-midnight (t/in-seconds (t/interval (t/date-time y m d) local-time)))
        (assoc :ping/tag-set (set (str/split tags #" "))))))

(s/def :ping/tags (s/and string? #(re-matches #"^[-:.\p{L}][-:.\p{L}0-9 ]*$" %)))

(s/def ::existing-ping
  (s/keys :opt [:ping/tags]))

(s/def ::new-ping
  (s/keys :req [:ping/tags :ping/ts]))

(defn is-valid-ping [db ping]
  (if (db/is-ping? db (:ping/ts ping))
    (update ping :ping/ts tc/from-long)
    :tictag.resources/unprocessable))

(defn update! [db uid id ping]
  (db/update-ping db uid id ping))

(defn create! [db uid ping]
  (db/create-ping db uid ping))

(defn upsert! [db uid ping]
  (db/upsert-ping db uid ping))

(defn location [e]
  (str "/api/ping/" (:ping/id e)))

(defn ping [{:keys [db]}]
  (resource
   resource-defaults
   :allowed-methods [:put]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid
        ::ping-id (id ctx)}))
   :can-put-to-missing? false
   :exists?
   (fn [ctx]
     (when-let [ping (out (db/get-ping db (::user-id ctx) (::ping-id ctx)))]
       {::ping ping}))
   :processable?
   (fn [ctx]
     (let [e (process ::existing-ping ctx [:ping/tags])]
       (if (= e :tictag.resources/unprocessable)
         [false nil]
         [true {::changes e}])))
   :put!
   (fn [ctx]
     (fn [] (assoc ctx ::ping (out (update! db (::user-id ctx) (::ping-id ctx) (::changes ctx))))))
   :new? false
   :respond-with-entity? true
   :handle-ok ::ping))

(defn pings [{:keys [db]}]
  (resource
   collection-defaults
   :allowed-methods [:get :post]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :handle-created ::ping
   :handle-ok (fn [ctx]
                (or (::ping ctx)
                    (map out (db/get-pings db (::user-id ctx)))))
   :new? #(if (::old-ping %) false true)
   :respond-with-entity? true
   :processable?
   (fn [ctx]
     (let [e (process ::new-ping ctx [:ping/ts :ping/tags] [(partial is-valid-ping db)])]
       (if (= e :tictag.resources/unprocessable)
         [false {::unprocessable "Invalid ping"}]
         [true {::ping e
                ::old-ping (db/get-ping db [:and
                                            [:= :user-id (::user-id ctx)]
                                            [:= :ts (:ping/ts e)]])}])))
   :post!
   (fn [ctx]
     {::ping (upsert! db
                      (::user-id ctx)
                      (assoc (::ping ctx)
                             :tz-offset (db/tz-offset (::user-id ctx) (:ping/ts (::ping ctx)))))})))


