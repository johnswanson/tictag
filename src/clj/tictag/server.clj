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
            [tictag.beeminder :as beeminder]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [tictag.utils :as utils]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]))

(defn str-number? [s]
  (try (Long. s) (catch Exception e nil)))

(defn parse-sms-body [body]
  (let [[cmd? & args :as all-args] (-> body
                                       (str/lower-case)
                                       (str/trim)
                                       (str/split #" "))]
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

(defn tag-ping-by-id [db {:keys [id] :as args}]
  (let [long-time (db/pending-timestamp db id)]
    (tag-ping-by-long-time db (assoc args :long-time long-time))))

(defn tag-last-ping [db {:keys [tags]}]
  (let [[last-ping] (db/get-pings
                     (:db db)
                     ["select * from pings order by ts desc limit 1"])]
    (db/update-tags! db [(assoc last-ping :tags tags)])))

(defn handle-sms [db body]
  (let [{:keys [command args]} (parse-sms-body body)]
    (timbre/debugf "Received SMS: %s, parsed as: %s %s" body command args)
    (case command
      :sleep                 (sleep db args)
      :tag-ping-by-id        (tag-ping-by-id db args)
      :tag-ping-by-long-time (tag-ping-by-long-time db args)
      :tag-last-ping         (tag-last-ping db args))))

(defn handle-timestamp [db beeminder timestamp tags local-time]
  (timbre/debugf "Received client: %s %s %s" timestamp tags local-time)
  (let [long-time (Long. timestamp)]
    (assert (db/is-ping? db long-time))
    (db/add-tags db long-time tags local-time)
    (beeminder/sync! beeminder (db/get-pings (:db db)))
    {:status 200 :body ""}))

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

(defn pings [db]
  (timbre/debugf "Received request!")
  (response (db/get-pings (:db db))))

(defn wrap-authenticate [handler f]
  (fn [req]
    (timbre/debugf "Authenticating! (f req) is : %s" (f req))
    (if (f req)
      (handler req)
      {:status 401 :body "unauthorized"})))

(defn sms [db beeminder req]
  (handle-sms db (get-in req [:params :Body]))
  (beeminder/sync! beeminder (db/get-pings (:db db)))
  (twilio/response "<Response></Response>"))

(defn timestamp [db beeminder {:keys [params]}]
  (handle-timestamp db beeminder (:timestamp params) (:tags params) (:local-time params)))

(defn valid-shared-secret? [shared-secret {:keys [params]}]
  (= shared-secret (:secret params)))

(defn routes [db beeminder twilio tagtime shared-secret]
  (compojure.core/routes
   (POST "/sms" _ (wrap-authenticate
                   (partial sms db beeminder)
                   (partial twilio/valid-sig? twilio)))
   (PUT "/time/:timestamp" _
     (wrap-authenticate
      (partial handle-timestamp db beeminder)
      (partial valid-shared-secret? shared-secret)))
   (GET "/pings" _ (pings db))
   (GET "/" _ (index))
   (GET "/config" _ {:headers {"Content-Type" "application/edn"}
                     :status 200
                     :body (pr-str {:tagtime-seed (:seed tagtime)
                                    :tagtime-gap  (:gap tagtime)})})
   (GET "/healthcheck" _ {:status  200
                          :headers {"Content-Type" "text/plain"}
                          :body    "healthy!"})))

(defrecord Server [db config tagtime beeminder twilio]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (let [stop (http/run-server
                (-> (routes db beeminder twilio tagtime (:shared-secret config))
                    (wrap-transit-response {:encoding :json})
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

