(ns tictag.server
  (:require [clj-time.format :as f]
            [clj-time.core :as t]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST DELETE context]]
            [taoensso.timbre]
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
            [tictag.jwt :as jwt]
            [tictag.schemas :as schemas]))

(taoensso.timbre/refer-timbre)

(def UNAUTHORIZED
  {:status 401
   :headers {"Content-Type" "text/plain"}
   :body "unauthorized"})

(defn index [component]
  (html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:link {:href "https://fonts.googleapis.com/css?family=Roboto:300,400,500,700,400italic"
             :rel "stylesheet"
             :type "text/css"}]
     [:link {:href "https://fonts.googleapis.com/css?family=Roboto+Condensed:300,400"
             :rel "stylesheet"
             :type "text/css"}]
     [:link {:rel "stylesheet" :href "//cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.5/css/bootstrap.css"}]
     [:link {:rel "stylesheet" :href "/css/re-com.css"}]
     [:link {:rel "stylesheet" :href "/css/material-design-iconic-font.min.css"}]
     [:link {:rel "stylesheet" :href "/css/main.css"}]]
    [:body
     [:div#app]
     [:script {} (format "var slack_client_id='%s'" (-> component :config :slack-client-id))]
     [:script {:src "/js/compiled/app.js"}]
     [:script {:src "https://use.fontawesome.com/efa7507d6f.js"}]]]))

(defn slack-user [db slack-message]
  (when-let [uid (get-in slack-message [:event :user])]
    (db/get-user-from-slack-id db uid)))

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
  (debugf "Sending slack message to %s" (pr-str (:username user)))
  (slack/send-message! user (apply format body-fmt)))

(defn report-changed-ping [old-ping new-ping]
  (format "Changing Ping @ `%s`\nOld: `%s`\nNew: `%s`"
          (:local-time new-ping)
          (:tags old-ping)
          (:tags new-ping)))

(defn tag-ping [db user old-ping tags]
  (let [new-ping (assoc old-ping :tags tags)]
    (update-pings! db user [new-ping])
    (slack! user [(report-changed-ping old-ping new-ping)])))

(defmulti apply-command! (fn [db user cmd args] cmd))

(defmethod apply-command! :make-pings-sleepy [db user _ args]
  (let [sleepy-pings (db/sleepy-pings db user)]
    (debugf "Making pings sleepy: %s" (pr-str sleepy-pings))
    (update-pings! db user (map #(assoc % :tags #{"sleep"}) sleepy-pings))
    (slack! user ["sleepings pings: %s to %s"
                  (:local-time (last sleepy-pings))
                  (:local-time (first sleepy-pings))])))

(defmethod apply-command! :tag-ping-by-id [db user _ {:keys [id tags]}]
  (tag-ping db user (db/ping-from-id db user id) tags))

(defmethod apply-command! :tag-ping-by-long-time [db user _ {:keys [tags long-time]}]
  (tag-ping db user (db/ping-from-long-time db user long-time) tags))

(defmethod apply-command! :tag-last-ping [db user _ {:keys [tags]}]
  (tag-ping db user (db/last-ping db user) tags))

(defmethod apply-command! :ditto [db user _ _]
  (let [[last-ping second-to-last-ping] (db/last-pings db user 2)]
    (tag-ping db user last-ping (:tags second-to-last-ping))))

(defmethod apply-command! :help [_ user _ _]
  (slack! user ["Tag the most recent ping (e.g. by saying `ttc`)
Tag a ping by its id (e.g. by saying `113 ttc`)
Tag a ping by its long-time (e.g. by saying `1494519002000 ttc`)
`sleep` command: tag the most recent set of contiguous pings as `sleep`
`\"` command: tags the last ping with whatever the second-to-last ping had
"]))

(defn apply-command [db user cmd]
  (debugf "COMMAND %s: %s" (pr-str (:username user)) (pr-str cmd))
  (let [{:keys [command args]} (cli/parse-body cmd)]
    (debugf "Command parsed as: %s, args %s" (pr-str command) (pr-str args))
    (apply-command! db user command args)))

(defn valid-slack? [{:keys [config]} params]
  (let [valid? (= (:token params) (:slack-verification-token config))]
    (when (not valid?)
      (warn "INVALID SLACK MESSAGE RECEIVED: %s" params))
    valid?))

(defn slack [{:keys [db] :as component} {:keys [params]}]
  (future
    (when-let [user (and (valid-slack? component params)
                       (slack-user db params))]
      (apply-command db user (slack-text params))))
  {:status 200 :body ""})

(defn timestamp [{:keys [db]} {:keys [params user-id]}]
  (if-let [user (db/get-user-by-id db user-id)]
    (do
      (debugf "Received a timestamp: %s" (pr-str params))
      (update-pings! db user [(-> params
                                  (assoc :user-id (:id user))
                                  (update :timestamp cli/str-number?)
                                  (dissoc :username :password))])
      (response {:status :ok}))
    UNAUTHORIZED))

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
    UNAUTHORIZED))

(defn signup [{:keys [db jwt]} {:keys [params]}]
  (let [[invalid? user] (schemas/validate
                         params
                         (schemas/+new-user-schema+ (set (map :name (db/timezones db)))))]
    (if invalid?
      UNAUTHORIZED
      (let [written? (db/write-user db user)
            [valid? token] (users/get-token
                            {:db db :jwt jwt}
                            (:username params)
                            (:password params))]
        {:status 200 :headers {} :body token}))))

(defn timezone-list [component _]
  (db/timezones (:db component)))

(defn slack-callback [{:keys [config db] :as component} {:keys [params user-id]}]
  (if user-id
    (let [code              (:code params)
          token-resp        (slack/oauth-access-token (:slack-client-id config)
                                                      (:slack-client-secret config)
                                                      code
                                                      (:slack-redirect-uri config))
          access-token      (token-resp :access-token)
          bot-access-token  (-> token-resp :bot :bot-access-token)
          slack-user-id     (:user-id token-resp)
          {:keys [user]}    (slack/users-info access-token slack-user-id)
          {:keys [channel]} (slack/im-open bot-access-token slack-user-id)]
      (if (and user-id user bot-access-token channel (:id channel) slack-user-id)
        (do
          (db/write-slack db {:id user-id} (:name user) bot-access-token (:id channel) slack-user-id)
          (index component))
        UNAUTHORIZED))
    UNAUTHORIZED))

(defn sanitize [user]
  (-> user
      (select-keys [:username :email :tz :beeminder :slack :id :pings])
      (update :beeminder #(select-keys % [:username :enabled? :token :goals :id]))
      (update :slack #(select-keys % [:username :id]))
      (update :beeminder #(if (seq %) % nil))
      (update :slack #(if (seq %) % nil))))

(defn my-user [{:keys [db]} {:keys [user-id]}]
  (if user-id
    (let [user  (db/get-user-by-id db user-id)
          goals (db/get-goals-raw db (:beeminder user))
          pings (unjoda (db/get-pings-by-user-id (:db db) user-id))]
      (response (sanitize (-> user
                              (assoc-in [:beeminder :goals] goals)
                              (assoc :pings pings)))))
    UNAUTHORIZED))

(defn delete-beeminder [{:keys [db]} {:keys [user-id]}]
  (if user-id
    (do (db/delete-beeminder db user-id)
        (response {}))
    UNAUTHORIZED))

(defn delete-slack [{:keys [db]} {:keys [user-id]}]
  (if user-id
    (do
      (db/delete-slack db user-id)
      (response {}))
    UNAUTHORIZED))

(defn add-beeminder [{:keys [db]} {:keys [params user-id]}]
  (if-let [bm-user (beeminder/user-for (:token params))]
    (try
      (response
       (db/write-beeminder
        db
        {:id user-id}
        (:username bm-user)
        (:token params)
        false))
      (catch Exception e UNAUTHORIZED))
    UNAUTHORIZED))

(defn add-goal [{:keys [db]} {:keys [params user-id]}]
  (let [new-id (:id (db/add-goal db user-id params))]
    (response (assoc params :goal/id new-id))))

(defn update-goal [{:keys [db]} {:keys [params user-id]}]
  (when-let [int-id (try (Integer. (:id params))
                         (catch Exception _ nil))]
    (db/update-goal db user-id (assoc params :goal/id int-id))
    (response {})))

(defn delete-goal [{:keys [db]} {:keys [params user-id]}]
  (when-let [int-id (try (Integer. (:id params))
                         (catch Exception _ nil))]
    (db/delete-goal db user-id int-id)
    (response {})))

(defn wrap-session-auth [handler jwt]
  (fn [req]
    (if (:user-id req)
      (handler req)
      (let [token (get-in req [:cookies "auth-token" :value])
            user (jwt/unsign jwt token)]
        (handler (assoc req :user-id (:user-id user)))))))

(defn routes [component]
  (compojure.core/routes
   (GET "/slack-callback" _ (wrap-session-auth (partial slack-callback component) (:jwt component)))
   (POST "/slack" _ (partial slack component))
   (PUT "/time/:timestamp" _ (partial timestamp component))
   (POST "/token" _ (partial token component))
   (GET "/pings" _ (partial pings component))
   (GET "/config" _ (partial config component))
   (GET "/" _ (index component))
   (GET "/signup" _ (index component))
   (GET "/login" _ (index component))
   (GET "/logout" _ (index component))
   (GET "/settings" _ (index component))
   (context "/api" []
            (GET "/timezones" _ (partial timezone-list component))
            (GET "/user/me" _ (partial my-user component))
            (POST "/user/me/beeminder" _ (partial add-beeminder component))
            (DELETE "/user/me/beeminder" _ (partial delete-beeminder component))
            (DELETE "/user/me/slack" _ (partial delete-slack component))
            (POST "/user/me/goals/" _ (partial add-goal component))
            (PUT "/user/me/goals/:id" _ (partial update-goal component))
            (DELETE "/user/me/goals/:id" _ (partial delete-goal component)))
   (POST "/signup" _ (partial signup component))
   (GET "/healthcheck" _ (health-check component))))

(defn wrap-user [handler jwt]
  (fn [req]
    (let [token (get-in req [:headers "authorization"])
          user  (jwt/unsign jwt token)]
      (handler (assoc req :user-id (:user-id user))))))

(defrecord Server [db config tagtime]
  component/Lifecycle
  (start [component]
    (debug "Starting server")
    (let [stop (http/run-server
                (-> (routes component)
                    (wrap-user (:jwt component))
                    (wrap-restful-format :formats [:json-kw :edn :transit-json :transit-msgpack])
                    (wrap-defaults (-> api-defaults
                                       (assoc-in [:static :resources] "/public")
                                       (assoc :cookies true))))
                config)]
      (debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

