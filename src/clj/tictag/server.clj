(ns tictag.server
  (:require [tictag.db :as db]
            [tictag.utils :as utils]
            [tictag.ws :as ws]
            [tictag.users :as users]
            [tictag.beeminder :as beeminder]
            [tictag.slack :as slack]
            [tictag.jwt :as jwt :refer [wrap-user]]
            [tictag.schemas :as schemas]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST DELETE context]]
            [ring.util.response :refer [response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults site-defaults]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [clojure.string :as str]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [tictag.tagtime :as tagtime]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]
            [instaparse.core :as insta]
            [clj-time.coerce :as tc]))

(timbre/refer-timbre)

(def wtf (f/formatter "yyyy-MM-dd HH:mm:ss"))

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

(defn devcards [component]
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
     [:script {:src "/js/tictag_devcards.js"}]
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
  (str
   (->> (str/replace (get-in slack-message [:event :text]) #"^<.+> " "")
        (str/lower-case))
   "\n"))

(def command-parser (insta/parser (clojure.java.io/resource "parser.bnf")))

(defmulti evaluate (fn [context [v & vs]] v))

(defmethod evaluate :CMDS [ctx [_ & vs]]
  (doseq [[cmd {:keys [saved error]}] (map (partial evaluate ctx) vs)]
    (when error (timbre/error error))))

(defmethod evaluate :ID [{:keys [db user]} [_ id-str]]
  (if-let [ping (db/ping-from-id db user id-str)]
    ping
    (do (slack/send-message user (format "Warning: can't find ping with id: `%s`" id-str)) nil)))

(defmethod evaluate :LT [{:keys [db user]} [_ lt-str]]
  (if-let [ping (db/ping-from-long-time db user (Long. lt-str))]
    ping
    (do (slack/send-message user (format "Warning: can't find ping from time: `%s`" lt-str)) nil)))

(defmethod evaluate :DITTO [{:keys [db user]} _]
  (vec (:tags (second (db/last-pings db user 2)))))

(defmethod evaluate :TAG [_ [_ tag]]
  tag)

(defmethod evaluate :HELP [_ v] v)

(defmethod evaluate :PREVPING [{:keys [db user] :as ctx} [_ & args]]
  [:SAVE (conj (map (partial evaluate ctx) args)
               (db/last-ping db user))])

(defmethod evaluate :TAGS [ctx [_ & tags :as all]]
  (flatten (map (partial evaluate ctx) tags)))

(defmethod evaluate :SLEEP [{:keys [db user] :as ctx} _]
  [:SAVE*
   (map
    (fn [ping]
      [ping ["sleep"]])
    (db/sleepy-pings db user))])

(defn update-pings [pings]
  (->> pings
       (map (fn [[p t]]
              (when p
                (assoc p :tags (set t) :_old-tags (:tags p)))))
       (remove nil?)))

(defn save-pings [{:keys [db user thread-ts]} pings]
  (db/update-tags! db pings)
  (slack/send-messages
   user
   (map
    (fn [{:keys [local-time tags _old-tags] :as ping}]
      (timbre/trace ping)
      {:text      (format "`updated %s:`\n`%s -> %s`"
                          (f/unparse wtf local-time)
                          _old-tags
                          tags)
       :thread-ts thread-ts})
    pings)))

(defn save* [{:keys [db user] :as ctx} ps]
  (let [new (update-pings ps)]
    (try (do (save-pings ctx new)
             {:saved new})
         (catch Exception e
           {:error {:exception e
                    :saving    ps}}))))

(defn save [ctx p]
  (save* ctx [p]))

(defn help [{:keys [slack user]}]
  (slack/send-message user "TicTag will ping you every 45 minutes, on average.
Pings look like this: `ping <id> [<long-time>]`
Tag the most recent ping (e.g. by saying `ttc`)
Tag a specific ping by responding in a slack thread to that ping's message
Tag a ping by its id (e.g. by saying `113 ttc`)
Tag a ping by its long-time (e.g. by saying `1494519002000 ttc`)
`sleep` command: tag the most recent set of contiguous pings as `sleep`
`\"`: macroexpands to the tags of the ping sent *before the one you're tagging*
Separate commands with a newline to apply multiple commands at once
")
  nil)

(defmethod evaluate :CMD [ctx [_ things]]
  (let [[cmd args] (evaluate ctx things)]
    [cmd
     (case cmd
       :SAVE  (save ctx args)
       :SAVE* (save* ctx args)
       :HELP  (help ctx)
       {:error {:exception :unknown-command
                :cmd cmd
                :args args}})]))

(defmethod evaluate :BYID [ctx [_ & args]]
  [:SAVE (map (partial evaluate ctx) args)])

(defmethod evaluate :BYLT [ctx [_ & args]]
  [:SAVE (map (partial evaluate ctx) args)])

(defn valid-slack? [{:keys [config]} params]
  (let [valid? (= (:token params) (:slack-verification-token config))]
    (when (not valid?)
      (warn "INVALID SLACK MESSAGE RECEIVED: %s" params))
    valid?))

(defn eval-command [ctx s]
  (try (evaluate ctx (command-parser s))
       (catch Exception e
         (timbre/error e)
         (timbre/error (command-parser s)))))

(defn slack [{:keys [db tagtime] :as component} {:keys [params]}]
  (taoensso.timbre/logged-future
   (when-let [user (and (valid-slack? component params)
                        (slack-user db params))]
     (let [evt    (:event params)
           me     (first (:authed_users params))
           dm?    (str/starts-with? (or (:channel evt) "") "D")
           to-me? (str/starts-with? (:text evt)
                                    (str "<@" me ">"))
           ctx    (assoc component :user user)]
       (timbre/debug [:slack-message
                      {:user   (:id user)
                       :dm?    dm?
                       :to-me? to-me?}])
       (when (or dm? to-me?)
         (if-let [thread-ts (some-> params :event :thread_ts)]
           (eval-command (-> ctx
                             (assoc :thread-ts thread-ts)
                             (update :db db/with-last-ping user thread-ts))
                         (slack-text params))
           (eval-command ctx (slack-text params)))
         (beeminder/sync! component user)))))
  {:status 200 :body ""})

(defn timestamp [{:keys [db] :as component} {:keys [params user-id]}]
  (if-let [user (db/get-user-by-id db user-id)]
    (do
      (taoensso.timbre/logged-future
       (debug [:timestamp {:user-id   (:id user)
                           :timestamp (:timestamp params)
                           :tags      (:tags params)}])
       (let [ping     (db/ping-from-long-time db user (Long. (:timestamp params)))
             new-ping (-> ping
                          (assoc :tags (set (:tags params))
                                 :_old-tags (:tags ping)))]
         (if ping
           (do (save-pings (assoc component :user user)
                           [new-ping])
               (beeminder/sync! component user))
           (slack/send-message
            user
            (format "WARNING: couldn't find ping with timestamp: `%s`"
                    (:timestamp params))))))
      (response {:status 200}))
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

(defn update-tz [{:keys [db]} {:keys [params user-id]}]
  (if user-id
    (try (response (do
                     (db/update-timezone db user-id (:tz params))
                     params))
         (catch Exception e
           (error e)
           UNAUTHORIZED))
    UNAUTHORIZED))

(defn add-goal [{:keys [db]} {:keys [params user-id]}]
  (if (spy (s/valid? :tictag.schemas/goal params))
    (let [new-id (:id (db/add-goal db user-id params))]
      (response (assoc params :goal/id new-id)))
    {:status 400}))

(defn update-goal [{:keys [db]} {:keys [params user-id]}]
  (if (spy (s/valid? :tictag.schemas/goal params))
    (when-let [int-id (utils/str-number? (:id params))]
      (db/update-goal db user-id (assoc params :goal/id int-id))
      (response {}))
    {:status 400}))

(defn delete-goal [{:keys [db]} {:keys [params user-id]}]
  (when-let [int-id (try (Integer. (:id params))
                         (catch Exception _ nil))]
    (db/delete-goal db user-id int-id)
    (response {})))

(defn tagtime-import-from-file [{:keys [db ws]} {:keys [multipart-params user-id params] :as req}]
  (taoensso.timbre/logged-future
   (if-let [parsed-all (seq
                        (partition-all
                         1000
                         (tagtime/parse
                          user-id
                          (slurp
                           (get-in multipart-params
                                   ["tagtime-log" :tempfile])))))]
     (doseq [[idx parsed] (map-indexed vector parsed-all)]
       (do
         (timbre/debugf "adding %d pings to user %d" (count parsed) user-id)
         (db/insert-tagtime-data db parsed)
         ((:chsk-send! ws) user-id [:tagtime-import/process-progress
                                    {:total     (count parsed-all)
                                     :filename  (get-in multipart-params ["tagtime-log" :filename])
                                     :processed (inc idx)}])))
     (debugf "Invalid tagtime data received from user-id %d" user-id)))
  (response {:accepted true}))

(defn enable-beeminder [{:keys [db]} {:keys [params user-id]}]
  (let [{:keys [enable?]} params]
    (db/enable-beeminder db user-id enable?)))

(defn wrap-log [handler]
  (fn [req]
    (when-not (or (= (:uri req) "/healthcheck") (= (:uri req) "/slack") (str/starts-with? (:uri req) "/js"))
      (debug [:request
              (select-keys req [:user-id :uri :remote-addr])]))
    (handler req)))

(defn site-routes [component]
  (-> (compojure.core/routes
       (GET "/slack-callback" _
            (jwt/wrap-session-auth
             (partial slack-callback component)
             (:jwt component)))
       (GET "/devcards" _ (devcards component))
       (GET "/" _ (index component))
       (GET "/signup" _ (index component))
       (GET "/login" _ (index component))
       (GET "/logout" _ (index component))
       (GET "/about" _ (index component))
       (GET "/settings" _ (index component))
       (PUT "/tagtime" _ (partial tagtime-import-from-file component))
       (GET "/healthcheck" _ (health-check component)))
      (wrap-defaults (-> site-defaults
                         (assoc-in [:security :anti-forgery] false)
                         (assoc :proxy true)))))

(defn api-routes [component]
  (-> (compojure.core/routes
       (POST "/signup" _ (partial signup component))
       (POST "/slack" _ (partial slack component))
       (PUT "/time/:timestamp" _ (partial timestamp component))
       (POST "/token" _ (partial token component))
       (GET "/pings" _ (partial pings component))
       (GET "/config" _ (partial config component))
       (context "/api" []
                (GET "/timezones" _ (partial timezone-list component))
                (GET "/user/me" _ (partial my-user component))
                (POST "/user/me/beeminder" _ (partial add-beeminder component))
                (POST "/user/me/beeminder/enable" _ (partial enable-beeminder component))
                (POST "/user/me/tz" _ (partial update-tz component))
                (DELETE "/user/me/beeminder" _ (partial delete-beeminder component))
                (DELETE "/user/me/slack" _ (partial delete-slack component))
                (POST "/user/me/goals/" _ (partial add-goal component))
                (PUT "/user/me/goals/:id" _ (partial update-goal component))
                (DELETE "/user/me/goals/:id" _ (partial delete-goal component))))
      (wrap-defaults (-> api-defaults (assoc :proxy true)))))


(defn my-routes [component]
  (wrap-restful-format
   (compojure.core/routes (site-routes component) (api-routes component))
   :formats [:json-kw :edn :transit-json :transit-msgpack]))

(defrecord Server [db config tagtime ws jwt]
  component/Lifecycle
  (start [component]
    (debug "Starting server")
    (let [stop (http/run-server
                (-> (compojure.core/routes
                     (my-routes component)
                     (ws/ws-routes ws))
                    (wrap-log)
                    (wrap-user (:jwt component))
                    (wrap-defaults {:params {:urlencoded true
                                             :keywordize true}}))
                config)]
      (debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

