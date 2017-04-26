(ns tictag.server
  (:require [clj-time.format :as f]
            [clj-time.core :as t]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST]]
            [taoensso.timbre :as timbre]
            [ring.util.response :refer [response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [tictag.db :as db]
            [clojure.string :as str]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [tictag.cli :as cli]
            [tictag.users :as users]
            [tictag.beeminder :as beeminder]
            [tictag.slack :as slack]
            [tictag.jwt :as jwt]))


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
  (timbre/tracef "Validating slack message: %s" slack-message)
  (db/get-user-from-slack-id db (get-in slack-message [:event :user])))

(defn api-user [db {:keys [username password]}]
  (db/authenticated-user db username password))

(defn unjoda [pings]
  (map #(update % :local-time
                (partial f/unparse
                         (f/formatter :basic-date-time))) pings))

(defn pings [{:keys [db]} {:keys [user-id]}]
  (when user-id
    (response (unjoda (db/get-pings-by-user-id (:db db) user-id)))))

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

(defn ditto [db user _]
  (let [[last-ping second-to-last-ping] (db/last-pings db user 2)]
    (tag-ping db user last-ping (:tags second-to-last-ping))))

(defn apply-command [db user cmd]
  (timbre/debugf "Applying command: %s" (pr-str cmd))
  (let [{:keys [command args]} (cli/parse-body cmd)
        f                      (case command
                                 :sleep                 make-pings-sleepy
                                 :ditto                 ditto
                                 :tag-ping-by-id        tag-ping-by-id
                                 :tag-ping-by-long-time tag-ping-by-long-time
                                 :tag-last-ping         tag-last-ping)]
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

(defn timestamp [{:keys [db]} {:keys [params user-id]}]
  (if (and (valid-timestamp? params) user-id)
    (when-let [user (db/get-user-by-id db user-id)]
      (timbre/debugf "Received a timestamp: %s"
                     (pr-str params))
      (update-pings! db user [(-> params
                                  (assoc :user-id (:id user))
                                  (update :timestamp cli/str-number?)
                                  (dissoc :username :password))])
      {:status 200 :body ""})
    {:status 401 :body "unauthorized"}))

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

(defn token [{:keys [db jwt]} {:keys [params]}]
  (let [[valid? token] (users/get-token
                        {:db db :jwt jwt}
                        (:username params)
                        (:password params))]
    (if valid?
      {:status 200 :headers {} :body token}
      {:status 401 :headers {} :body nil})))

(defn config [component req]
  (if-let [user-id (:user-id req)]
    {:headers {"Content-Type" "application/edn"}
     :status 200
     :body (pr-str {:tagtime-seed (-> component :tagtime :seed)
                    :tagtime-gap  (-> component :tagtime :gap)})}
    {:status 401
     :body "unauthorized"}))

(defn routes [component]
  (compojure.core/routes
   (POST "/slack" _ (partial slack component))
   (PUT "/time/:timestamp" _ (partial timestamp component))
   (POST "/token" _ (partial token component))
   (GET "/pings" _ (partial pings component))
   (GET "/config" _ (partial config component))
   (GET "/" _ index)
   (GET "/healthcheck" _ (health-check component))))

(defn wrap-user [handler jwt]
  (fn [req]
    (let [token (get-in req [:headers "authorization"])
          user  (jwt/unsign jwt token)]
      (handler (assoc req :user-id (:user-id user))))))

(defrecord Server [db config tagtime]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (let [stop (http/run-server
                (-> (routes component)
                    (wrap-user (:jwt component))
                    (wrap-restful-format :formats [:json-kw :edn :transit-json :transit-msgpack])
                    (wrap-defaults (assoc-in api-defaults [:static :resources] "/public")))
                config)]
      (timbre/debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (timbre/debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

