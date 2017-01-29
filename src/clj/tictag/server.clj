(ns tictag.server
  (:require [clj-time.format :as f]
            [clj-time.core :as t]
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
            [clojure.string :as str]
            [clojure.java.io :as io]
            [tictag.utils :as utils]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]))

(def delimiters #"[ ,]")

(defn str-number? [s]
  (try (Long. s) (catch Exception e nil)))

(defn parse-body [body]
  (let [[id & tags] (str/split (str/lower-case body) delimiters)]
    {:id id
     :tags tags}))

(defn parse-sms-body [body]
  (let [[cmd? & args :as all-args] (str/split (str/lower-case body) #" ")]
    (if (str-number? cmd?)
      (if (> (count cmd?) 3)
        {:command :tag-ping-by-long-time
         :args {:tags args
                :long-time (Long. cmd?)}}
        {:command :tag-ping-by-id
         :args {:tags args
                :id cmd?}})

      (if (and (= cmd? "sleep") (= (count args) 0))
        {:command :sleep
         :args {}}
        {:command :tag-last-ping
         :args {:tags all-args}}))))

(defn sleep [db _]
  (let [pings (db/sleepy-pings db)]
    (db/make-pings-sleepy! db (db/sleepy-pings db))
    (twilio/response
     (format
      "<Response><Message>Sleeping pings from %s to %s</Message></Response>"
      (:local-time (last pings))
      (:local-time (first pings))))))

(defn tag-ping-by-long-time [db {:keys [long-time tags]}]
  (assert long-time)
  (db/add-tags db long-time tags (utils/local-time-from-long long-time)))

(defn tag-ping-by-id [db {:keys [id tags]}]
  (let [long-time (db/pending-timestamp db id)]
    (tag-ping-by-long-time db long-time tags)))

(defn tag-last-ping [db {:keys [tags]}]
  (let [[last-ping] (db/get-pings
                     (:db db)
                     ["select * from pings order by timestamp desc limit 1"])]
    (db/update-tags! db [(assoc last-ping :tags tags)])))

(defn handle-sms [db body]
  (let [{:keys [command args]} (parse-sms-body body)]
    (case command
      :sleep                 (sleep db args)
      :tag-ping-by-id        (tag-ping-by-id db args)
      :tag-ping-by-long-time (tag-ping-by-long-time db args)
      :tag-last-ping         (tag-last-ping db args))))

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

