(ns tictag.server
  (:require [clj-time.coerce :as tc]
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

(def sms
  (POST "/sms" [Body :as {db :db}]
        (let [{:keys [id tags]} (parse-body Body)
              long-time (get-in db [:pends id])]
          (assert long-time)
          (db/add-tags db long-time tags)
          (twilio/response
           (format
            "<Response><Message>Thanks for %s</Message></Response>"
            id)))))

(def timestamp
  (PUT "/time/:timestamp" [timestamp tags local-time :as {db :db body :body}]
       (timbre/debugf "Received client: %s %s %s" timestamp tags local-time)
       (let [long-time (Long. timestamp)]
         (assert (tictag.tagtime/is-ping? long-time))
         (db/add-tags db long-time tags local-time)
         {:status 200 :body ""})))

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
                                (wrap-db (:db db))
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
          (swap! (:db db)
                 (fn [db]
                   (-> db
                       (assoc-in [:pends id] long-time)
                       (update-in [:pings long-time] identity))))
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
   :db (db/->Database)
   :chimer (component/using
            (map->ServerChimer {})
            [:db])))


