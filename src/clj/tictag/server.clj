(ns tictag.server
  (:require [clj-time.coerce :as tc]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [chime :refer [chime-at]]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST]]
            [taoensso.timbre :as timbre]
            [ring.util.response :refer [response]]
            [ring.middleware.edn :refer [wrap-edn-params]]
            [ring.middleware.transit :refer [wrap-transit-params wrap-transit-response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [tictag.twilio :as twilio]
            [tictag.db :as db]
            [tictag.config :as config :refer [config]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [tictag.utils :as utils]
            [tictag.tagtime :as tagtime]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]))

(def delimiters #"[ ,]")

(defn parse-body [body]
  (let [[id & tags] (str/split (str/lower-case body) delimiters)]
    {:id id
     :tags tags}))

(defn handle-sms [db body]
  (let [[cmd & args] (str/split (str/lower-case body) #" ")]
    (case cmd

      ;; this is the only command implemented so far...
      "sleep"
      (let [pings (db/sleepy-pings db)]
        (db/make-pings-sleepy! db (db/sleepy-pings db))
        (twilio/response (format "<Response><Message>Sleeping pings from %s to %s</Message></Response>"
                                 (:local-time (first pings))
                                 (:local-time (last pings)))))

      ;; default
      (let [id        cmd
            tags      args
            long-time (db/pending-timestamp db id)]
        (timbre/debugf "Handling SMS. id: %s, tags: %s, long-time: %s" (pr-str id) (pr-str tags) (pr-str long-time))
        (assert long-time)
        (db/add-tags db long-time tags (utils/local-time-from-long long-time))
        (twilio/response
         (format
          "<Response><Message>Thanks for %s</Message></Response>"
          id))))))

(def sms (POST "/sms" [Body :as {db :db}] (handle-sms db Body)))

(defn handle-timestamp [db timestamp tags local-time]
  (timbre/debugf "Received client: %s %s %s" timestamp tags local-time)
  (let [long-time (Long. timestamp)]
    (assert (db/is-ping? db long-time))
    (db/add-tags db long-time tags local-time)
    {:status 200 :body ""}))

(def timestamp
  (PUT "/time/:timestamp" [secret timestamp tags local-time :as {db :db shared-secret :shared-secret}]
    (if (= secret shared-secret)
      (handle-timestamp db timestamp tags local-time)
      {:status 401 :body "unauthorized"})))

(defn wrap-db [handler db]
  (fn [req]
    (handler (assoc req :db db))))

(defn wrap-shared-secret [handler secret]
  (fn [req]
    (handler (assoc req :shared-secret secret))))

(defn index []
  (html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:link {:href "http://fonts.googleapis.com/css?family=Roboto:400,300,200"
             :rel "stylesheet"
             :type "text/css"}]
     [:link {:rel "stylesheet" :href "/css/app.css"}]]
    [:body
     [:div#app]
     [:script {:src "/js/compiled/app.js"}]
     [:script {:src "https://use.fontawesome.com/efa7507d6f.js"}]]]))

(defn pings [{db :db}]
  (timbre/debugf "Received request!")
  (response (db/get-pings (:db db))))

(def routes
  (compojure.core/routes
   sms
   timestamp
   (GET "/pings" [] pings)
   (GET "/" [] (index))
   (GET "/config" [] {:headers {"Content-Type" "application/edn"}
                      :status 200
                      :body (pr-str {:tagtime-seed (:tagtime-seed config)
                                     :tagtime-gap  (:tagtime-gap config)})})
   (GET "/healthcheck" [] {:status  200
                           :headers {"Content-Type" "text/plain"}
                           :body    "healthy!"})))

(defrecord Server [db config shared-secret]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (assoc component :stop (http/run-server
                            (-> routes
                                (wrap-transit-response {:encoding :json})
                                (wrap-shared-secret shared-secret)
                                (wrap-db db)
                                (wrap-defaults (assoc-in api-defaults [:static :resources] "/public"))
                                (wrap-edn-params)
                                (wrap-transit-params))
                            config)))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

(defrecord ServerChimer [db]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server chimer (for twilio sms)")
    (let [state (atom (cycle (shuffle (range 1000))))
          next! (fn [] (swap! state next) (str (first @state)))]
      (assoc
       component
       :stop
       (chime-at
        (db/pings db)
        (fn [time]
          (let [long-time (tc/to-long time)
                id        (next!)]
            (timbre/debug "CHIME!")
            (db/add-pending! db long-time id)
            (twilio/send-message!
             config/twilio
             (:text-number config)
             (format "PING %s" id))))))))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

(def system
  (let [tagtime (tagtime/tagtime (:tagtime-gap config) (:tagtime-seed config))]
    {:server (component/using
              (map->Server
               {:config config/server
                :shared-secret (:tictag-shared-secret config)})
              [:db])
     :db (db/->Database (:server-db-file config) tagtime)
     :chimer (component/using
              (map->ServerChimer {})
              [:db])}))



