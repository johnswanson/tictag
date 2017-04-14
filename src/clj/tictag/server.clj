(ns tictag.server
  (:require [clj-time.format :as f]
            [clj-time.core :as t]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST]]
            [taoensso.timbre :as timbre]
            [ring.util.response :refer [response]]
            [ring.middleware.json :refer [wrap-json-params]]
            [ring.middleware.edn :refer [wrap-edn-params]]
            [ring.middleware.transit :refer [wrap-transit-params wrap-transit-response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [tictag.db :as db]
            [clojure.string :as str]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [tictag.cli :as cli]
            [tictag.beeminder :as beeminder]
            [tictag.slack :as slack]))

(def index
  (html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:link {:href "https://fonts.googleapis.com/css?family=Roboto:400,300,200"
             :rel "stylesheet"
             :type "text/css"}]
     [:link {:rel "stylesheet" :href "/css/app.css"}]]
    [:body
     [:div#app]
     [:script {:src "/js/compiled/app.js"}]
     [:script {:src "https://use.fontawesome.com/efa7507d6f.js"}]]]))

(defn slack-user [db slack-message]
  (db/get-user-from-slack-id db (get-in slack-message [:event :user])))

(defn api-user [db {:keys [username password]}]
  (db/authenticated-user db username password))

(defn pings [{:keys [db]} {:keys [params]}]
  (response (db/get-pings-by-user (:db db) (api-user db params))))

(defn slack-text [slack-message]
  (get-in slack-message [:event :text]))

(defn update-pings! [db user pings]
  (db/update-tags! db pings)
  (beeminder/sync! db user))

(defn slack! [user body-fmt]
  (timbre/debugf "Sending slack message to %s" (pr-str (:username user)))
  (slack/send-message! user (apply format body-fmt)))

(defn make-pings-sleepy [db user args]
  (let [sleepy-pings (db/sleepy-pings db user)]
    (timbre/debugf "Making pings sleepy: %s" (pr-str sleepy-pings))
    (update-pings! db user (map #(assoc % :tags #{"sleep"}) sleepy-pings))
    (slack! user ["sleepings pings: %s to %s"
                  (:local-time (last sleepy-pings))
                  (:local-time (first sleepy-pings))])))

(defn report-changed-ping [old-ping new-ping]
  (format "Changing Ping @ `%s`\nOld: `%s`\nNew: `%s`"
          (:local-time new-ping)
          (:tags old-ping)
          (:tags new-ping)))

(defn tag-ping [db user old-ping tags]
  (let [new-ping (assoc old-ping :tags tags)]
    (update-pings! db user [new-ping])
    (slack! user [(report-changed-ping old-ping new-ping)])))

(defn tag-ping-by-id [db user {:keys [id tags]}]
  (tag-ping db user (db/ping-from-id db user id) tags))

(defn tag-ping-by-long-time [db user {:keys [tags long-time]}]
  (tag-ping db user (db/ping-from-long-time db user long-time) tags))

(defn tag-last-ping [db user {:keys [tags]}]
  (tag-ping db user (db/last-ping db user) tags))

(defn apply-command [db user cmd]
  (timbre/debugf "Applying command: %s" (pr-str cmd))
  (let [{:keys [command args]} (cli/parse-body cmd)
        f (case command
            :sleep make-pings-sleepy
            :tag-ping-by-id tag-ping-by-id
            :tag-ping-by-long-time tag-ping-by-long-time
            :tag-last-ping tag-last-ping)]
    (timbre/debugf "Command parsed as: %s, args %s" (pr-str command) (pr-str args))
    (f db user args)))

(defn valid-slack? [params] true)
(defn valid-timestamp? [params] true)

(defn slack [{:keys [db]} {:keys [params]}]
  (future
    (when (valid-slack? params)
      (timbre/debug "Validation: looks good!")
      (when-let [user (slack-user db params)]
        (timbre/debugf "Received a slack message: %s, from %s"
                       (pr-str (get-in params [:event :text]))
                       (pr-str (:username user)))
        (apply-command db user (slack-text params)))))
  {:status 200 :body ""})

(defn timestamp [{:keys [db]} {:keys [params]}]
  (future
    (when (valid-timestamp? params)
      (when-let [user (api-user db params)]
        (timbre/debugf "Received a timestamp: %s"
                       (pr-str params))
        (update-pings! db user [(-> params
                                    (assoc :user-id (:id user))
                                    (update :timestamp cli/str-number?)
                                    (dissoc :username :password))]))))
  {:status 200 :body ""})

(defn health-check [component]
  (fn [request]
    (let [db (:db component)]
      (if (db/test-query! db)
        {:status  200
         :headers {"Content-Type" "text/plain"}
         :body    "healthy!"}
        {:status 500
         :headers {"Content-Type" "text/plain"}
         :body "ERROR - NO DB CONNECTION"}))))

(defn routes [component]
  (compojure.core/routes
   (POST "/slack" _ (partial slack component))
   (PUT "/time/:timestamp" _ (partial timestamp component))
   (GET "/pings" _ (partial pings component))
   (GET "/" _ index)
   (GET "/config" _ {:headers {"Content-Type" "application/edn"}
                     :status 200
                     :body (pr-str {:tagtime-seed (:seed (:tagtime component))
                                    :tagtime-gap  (:gap (:tagtime component))})})
   (GET "/healthcheck" _ (health-check component))))

(defrecord Server [db config tagtime]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (let [stop (http/run-server
                (-> (routes component)
                    (wrap-transit-response {:encoding :json})
                    (wrap-defaults (assoc-in api-defaults [:static :resources] "/public"))
                    (wrap-edn-params)
                    (wrap-json-params)
                    (wrap-transit-params))
                config)]
      (timbre/debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (timbre/debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

