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

(s/def ::existing-ping
  (s/keys :opt [:ping/tags]))

(defn update! [db uid id ping]
  (db/update-ping db uid id ping))

(defn update-ts! [db uid ts ping]
  (db/update-ping
   db
   [:and [:= :ts ts] [:= :user-id uid]]
   ping))

(defn create! [db uid ping]
  (db/create-ping db uid ping))

(defn location [e]
  (str "/api/ping/" (:ping/id e)))

(defn ping-by-ts [{:keys [db]}]
  (resource
   resource-defaults
   :allowed-methods [:put]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid
        ::ping-long (some-> ctx :request :route-params :ts Long.)
        ::ping-ts (some-> ctx :request :route-params :ts Long. tc/from-long)}))
   :can-put-to-missing? (fn [ctx]
                          (db/is-ping? db (::ping-long ctx)))
   :exists?
   (fn [ctx]
     (when-let [ping (out (db/get-ping db [:and
                                           [:= :user-id (::user-id ctx)]
                                           [:= :ts (::ping-ts ctx)]]))]
       {::ping ping}))
   :processable?
   (fn [ctx]
     (let [e (process ::existing-ping ctx [:ping/tags])]
       (if (= e :tictag.resources/unprocessable)
         [false nil]
         [true {::changes e}])))
   :put!
   (fn [ctx]
     (fn []
       (assoc ctx ::ping (out (update-ts! db (::user-id ctx) (::ping-ts ctx) (::changes ctx))))))
   :new? false
   :respond-with-entity? true
   :handle-ok ::ping))

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
   :allowed-methods [:get]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :exists?
   (fn [ctx]
     (fn []
       (when-let [pings (map out (db/get-pings db (::user-id ctx)))]
         (assoc ctx ::pings pings))))
   :handle-ok ::pings))


