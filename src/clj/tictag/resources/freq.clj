(ns tictag.resources.freq
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :as utils :refer [id uid params processable? process replace-keys query-fn]]
            [tictag.db :as db]
            [tictag.filters]
            [buddy.core.codecs.base64 :as b64]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.string :as str]))

(defn freq [{:keys [db]}]
  (resource
   resource-defaults
   :allowed-methods [:get]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid
        ::query (some-> ctx :request :route-params :query utils/b64-decode)}))
   :handle-ok (fn [ctx]
                {:count (count (filter (query-fn (::query ctx)) (db/get-pings db (::user-id ctx))))})))

(defn freqs [{:keys [db]}]
  (resource
   collection-defaults
   :allowed-methods [:get]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :handle-ok (fn [ctx]
                (db/get-freqs db (::user-id ctx)))))

(defn slices [ctx]
  (:slices (params ctx) []))

(defn filters [ctx]
  (:filters (params ctx) []))

(defn query [{:keys [db]}]
  (resource
   collection-defaults
   :allowed-methods [:get]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :handle-ok (fn [ctx]
                (let [pings (db/get-pings db (::user-id ctx))]
                  (tictag.filters/sieve pings {:slices (slices ctx)
                                               :filters (filters ctx)})))))

