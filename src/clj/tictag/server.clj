(ns tictag.server
  (:require [clj-time.coerce :as tc]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [clojure.tools.nrepl.server :as repl]
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
    (cond

      ;; this is the only command implemented so far...
      (= cmd "sleep")
      (let [pings (db/sleepy-pings db)]
        (db/make-pings-sleepy! db (db/sleepy-pings db))
        (twilio/response (format "<Response><Message>Sleeping pings from %s to %s</Message></Response>"
                                 (:local-time (first pings))
                                 (:local-time (last pings)))))

      ;; lookup by id
      (db/pending-timestamp db cmd)
      (let [id        cmd
            tags      args
            long-time (db/pending-timestamp db id)]
        (timbre/debugf "Handling SMS. id: %s, tags: %s, long-time: %s" (pr-str id) (pr-str tags) (pr-str long-time))
        (assert long-time)
        (db/add-tags db long-time tags (utils/local-time-from-long long-time))
        (twilio/response "<Response></Response>"))

      ;; maybe command is a long-time itself!
      (db/is-ping? db (Long. cmd))
      (let [long-time (Long. cmd)
            tags      args]
        (db/add-tags db long-time tags (utils/local-time-from-long long-time)))

      :else
      (let [tags      args
            last-ping (db/get-pings (:db db) ["select * from pings order by timestamp desc limit 1"])]
        (db/update-tags! db [(assoc last-ping :tags args)])
        (twilio/response
         (format
          "<Response><Message>Updated ping with timestamp %s, old pings: %s, new pings: %s</Message></Response>"
          (:timestamp last-ping)
          (pr-str (:tags last-ping))
          (pr-str (set args))))))))

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

(defn routes [tagtime]
  (compojure.core/routes
   sms
   timestamp
   (GET "/pings" [] pings)
   (GET "/" [] (index))
   (GET "/config" [] {:headers {"Content-Type" "application/edn"}
                      :status 200
                      :body (pr-str {:tagtime-seed (:seed tagtime)
                                     :tagtime-gap  (:gap tagtime)})})
   (GET "/healthcheck" [] {:status  200
                           :headers {"Content-Type" "text/plain"}
                           :body    "healthy!"})))

(defrecord Server [db config tagtime]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (let [stop (http/run-server
                (-> (routes tagtime)
                    (wrap-transit-response {:encoding :json})
                    (wrap-shared-secret (:shared-secret config))
                    (wrap-db db)
                    (wrap-defaults (assoc-in api-defaults [:static :resources] "/public"))
                    (wrap-edn-params)
                    (wrap-transit-params))
                config)]
      (timbre/debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (timbre/debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

(defrecord ServerChimer [db config]
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
             config
             (format "PING! id: %s, long-time: %d" id long-time))))))))
  (stop [component]
    (when-let [stop (:stop component)]
      (stop))

    (dissoc component :stop)))

(defrecord REPL []
  component/Lifecycle
  (start [component]
    (assoc component :server (repl/start-server :port 7888)))
  (stop [component]
    (when-let [server (:server component)]
      (repl/stop-server server))
    (dissoc component :server)))

(defn system [config]
  {:server (component/using
            (map->Server
             {:config (:tictag-server config)})
            [:db :tagtime])
   :tagtime (tagtime/tagtime (get-in config [:tagtime :gap]) (get-in config [:tagtime :seed]))
   :repl-server (->REPL)
   :db (component/using
        (db/map->Database {:file (get-in config [:db :file])})
        [:tagtime])
   :chimer (component/using
            (map->ServerChimer
             {:config (:twilio config)})
            [:db])})



