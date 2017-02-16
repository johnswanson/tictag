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

(defn update-ping
  [{:keys [db calendar beeminder]} {:as ping :keys [timestamp]}]
  (timbre/debugf "Updating ping: %s" (pr-str ping))
  (db/update-tags! db ping)
  (beeminder/sync! beeminder (db/get-pings (:db db)))
  (calendar ping))

(defn update-pings
  [{:keys [db calendar beeminder]} pings]
  (timbre/debugf "Updating pings: %s" (pr-str pings))
  (doseq [{:as ping :keys [timestamp]} pings]
    (db/update-tags! db ping)
    (future (calendar ping)))
  (beeminder/sync! beeminder (db/get-pings (:db db))))

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

(defn sleep [component _]
  (let [pings (db/sleepy-pings (:db component))]
    (update-pings component (map #(assoc % :tags #{"sleep"}) pings))))

(defn tag-ping-by-long-time [component {:keys [long-time tags]}]
  (assert long-time)
  (update-ping component {:timestamp  long-time
                          :tags       tags}))

(defn tag-ping-by-id [component {:keys [id] :as args}]
  (let [long-time (db/pending-timestamp (:db component) id)]
    (tag-ping-by-long-time component (assoc args :long-time long-time))))

(defn tag-last-ping [component {:keys [tags]}]
  (let [[last-ping] (db/get-pings
                     (:db (:db component))
                     ["select * from pings order by ts desc limit 1"])]
    (update-ping component (assoc last-ping :tags tags))))

(defn handle-sms [component body]
  (let [{:keys [command args]} (parse-sms-body body)]
    (timbre/debugf "Received SMS: %s, parsed as: %s %s" body command args)
    (case command
      :sleep                 (sleep component args)
      :tag-ping-by-id        (tag-ping-by-id component args)
      :tag-ping-by-long-time (tag-ping-by-long-time component args)
      :tag-last-ping         (tag-last-ping component args))))

(defn handle-timestamp [component params]
  (timbre/debugf "Received client: %s" (pr-str params))
  (let [ping (update params :timestamp str-number?)]
    (assert (db/is-ping? (:db component) (:timestamp ping)))
    (update-ping component ping)))

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

(defn pings [{:keys [db]}]
  (timbre/debugf "Received request!")
  (response (db/get-pings (:db db))))

(defn wrap-authenticate [handler f]
  (fn [req]
    (timbre/debugf "Authenticating! (f req) is : %s" (f req))
    (if (f req)
      (handler req)
      {:status 401 :body "unauthorized"})))

(defn sms [component req]
  (handle-sms component (get-in req [:params :Body]))
  (twilio/response "<Response></Response>"))

(defn timestamp [component {:keys [params]}]
  (handle-timestamp component params)
  {:status 200 :body ""})

(defn valid-shared-secret? [shared-secret {:keys [params]}]
  (= shared-secret (:secret params)))

(defn verify-valid-sig [{:keys [twilio]} req]
  (twilio/valid-sig? twilio req))

(defn verify-valid-secret [{:keys [config]} {:keys [params]}]
  (= (:shared-secret config)
     (:secret params)))

(defn routes [component]
  (compojure.core/routes
   (POST "/sms" _ (wrap-authenticate
                   (partial sms component)
                   (partial verify-valid-sig component)))
   (PUT "/time/:timestamp" _
     (wrap-authenticate
      (partial timestamp component)
      (partial verify-valid-secret component)))
   (GET "/pings" _ (pings component))
   (GET "/" _ (index))
   (GET "/config" _ {:headers {"Content-Type" "application/edn"}
                     :status 200
                     :body (pr-str {:tagtime-seed (:seed (:tagtime component))
                                    :tagtime-gap  (:gap (:tagtime component))})})
   (GET "/healthcheck" _ {:status  200
                          :headers {"Content-Type" "text/plain"}
                          :body    "healthy!"})))

(defrecord Server [db config tagtime beeminder twilio]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (let [stop (http/run-server
                (-> (routes component)
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

