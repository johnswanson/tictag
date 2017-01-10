(ns tictag.server
  (:require [clj-time.coerce :as tc]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [chime :refer [chime-at]]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST]]
            [taoensso.timbre :as timbre]
            [ring.middleware.edn :refer [wrap-edn-params]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [tictag.twilio :as twilio]
            [tictag.db :as db]
            [tictag.tagtime]
            [tictag.config :as config :refer [config]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def delimiters #"[ ,]")

(defn parse-body [body]
  (let [[id & tags] (str/split (str/lower-case body) delimiters)]
    {:id id
     :tags (set tags)}))

(defn local-time [long-time]
  (f/unparse
   (f/formatters :date-hour-minute-second)
   (t/to-time-zone (tc/from-long long-time)
                   (t/default-time-zone))))

(defn handle-sms [db body]
  (let [{:keys [id tags]} (parse-body body)
        long-time (db/pending-timestamp db id)]
    (timbre/debugf "Handling SMS. id: %s, tags: %s, long-time: %s" (pr-str id) (pr-str tags) (pr-str long-time))
    (assert long-time)
    (db/add-tags db long-time tags (local-time long-time))
    (twilio/response
     (format
      "<Response><Message>Thanks for %s</Message></Response>"
      id))))

(def sms (POST "/sms" [Body :as {db :db}] (handle-sms db Body)))

(defn handle-timestamp [db timestamp tags local-time]
  (timbre/debugf "Received client: %s %s %s" timestamp tags local-time)
  (let [long-time (Long. timestamp)]
    (assert (tictag.tagtime/is-ping? long-time))
    (db/add-tags db long-time tags local-time)
    {:status 200 :body ""}))

(def timestamp
  (PUT "/time/:timestamp" [timestamp tags local-time :as {db :db}]
    (handle-timestamp db timestamp tags local-time)))

(defn wrap-db [handler db]
  (fn [req]
    (handler (assoc req :db db))))

(def routes
  (compojure.core/routes
   sms
   timestamp))

(defrecord Server [db config]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (assoc component :stop (http/run-server
                            (-> routes
                                (wrap-db db)
                                (wrap-defaults api-defaults)
                                (wrap-edn-params))
                            config)))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

(defrecord ServerChimer [db]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server chimer (for twilio sms)")
    (assoc
     component
     :stop
     (chime-at
      tictag.tagtime/pings
      (fn [time]
        (let [long-time (tc/to-long time)
              id        (str (rand-int 1000))]
          (timbre/debug "CHIME!")
          (db/add-pending! db long-time id)
          (twilio/send-message!
           config/twilio
           (:text-number config)
           (format "PING %s" id)))))))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

(def system
  (component/system-map
   :server (component/using
            (map->Server
             {:config config/server})
            [:db])
   :db (db/->Database (:server-db-file config))
   :chimer (component/using
            (map->ServerChimer {})
            [:db])))


