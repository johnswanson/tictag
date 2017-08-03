(ns tictag.server
  (:require [tictag.db :as db]
            [tictag.utils :as utils]
            [tictag.ws :as ws]
            [tictag.users :as users]
            [tictag.beeminder :as beeminder]
            [tictag.slack :as slack]
            [tictag.jwt :as jwt :refer [wrap-user]]
            [tictag.schemas :as schemas]
            [tictag.tagtime :as tagtime]
            [tictag.events]
            [tictag.resources.config]
            [tictag.resources.timezone]
            [tictag.resources.user]
            [tictag.resources.macro]
            [tictag.resources.ping]
            [tictag.resources.slack]
            [tictag.resources.beeminder]
            [tictag.resources.goal]
            [tictag.resources.token]
            [taoensso.sente :as sente]
            [clj-time.format :as f]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST ANY context]]
            [ring.util.response :refer [response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults site-defaults]]
            [ring.middleware.format-params :refer [wrap-restful-params]]
            [clojure.string :as str]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]
            [taoensso.timbre :as timbre]
            [instaparse.core :as insta]))

(timbre/refer-timbre)

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

(defn slack-text [slack-message]
  (str
   (str/replace (get-in slack-message [:event :text]) #"^<.+> " "") "\n"))

(def command-parser (insta/parser (clojure.java.io/resource "parser.bnf") :string-ci true))

(defmulti evaluate (fn [context [v & vs]] v))

(defmethod evaluate :CMDS [ctx [_ & vs]]
  (timbre/trace [:evaluate :CMDS vs])
  (doseq [[cmd {:keys [saved error]}] (map (partial evaluate ctx) vs)]
    (when error (timbre/error error))))

(defmethod evaluate :ID [{:keys [db user]} [_ id-str]]
  (if-let [ping (db/ping-from-id db user id-str)]
    ping
    (do (slack/send-message user (format "Warning: can't find ping with id: `%s`" id-str)) nil)))

(defmethod evaluate :LT [{:keys [db user]} [_ lt-str]]
  (if-let [ping (db/get-ping db [:and
                                 [:= :user-id (:id user)]
                                 [:= :ts (Long. lt-str)]])]
    ping
    (do (slack/send-message user (format "Warning: can't find ping from time: `%s`" lt-str)) nil)))

(defmethod evaluate :DITTO [{:keys [db user]} _]
  (vec (:tags (second (db/last-pings db user 2)))))

(defmethod evaluate :TAG [{{macros :macros} :user} [_ tag]]
  (let [lc (str/lower-case tag)]
    (get macros lc lc)))

(defmethod evaluate :HELP [_ v] v)

(defmethod evaluate :PREVPING [{:keys [db user] :as ctx} [_ & args]]
  [:SAVE (conj (map (partial evaluate ctx) args)
               (db/last-ping db user))])

(defmethod evaluate :TAGS [ctx [_ & tags :as all]]
  (flatten (map (partial evaluate ctx) tags)))

(defmethod evaluate :SLEEP [{:keys [db user] :as ctx} _]
  (let [sleepy-pings (db/sleepy-pings db user)]
    (trace [:sleepy-pings (:id user) (vec sleepy-pings)])
    [:SAVE*
     (map
      (fn [ping]
        [ping ["sleep"]])
      sleepy-pings)]))

(defn update-pings [pings]
  (->> pings
       (map (fn [[p t]]
              (when p
                (assoc p :tags (set t) :_old-tags (:tags p)))))
       (remove nil?)))

(defn save-pings [{:keys [db user]} pings]
  (trace [:save-pings (:id user) pings])
  (db/update-tags! db pings)
  (slack/send-messages
   user
   (map
    (fn [{:keys [local-time tags _old-tags] :as ping}]
      (timbre/trace ping)
      {:text      (format "updated `%s: %s -> %s`"
                          (f/unparse utils/wtf local-time)
                          _old-tags
                          tags)})
    pings)))

(defn save* [{:keys [db user] :as ctx} ps]
  (trace [:save* (:id user) ps])
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
  (let [parse-result (command-parser s)]
    (if (insta/failure? parse-result)
      (do
        (slack/send-message
         (:user ctx)
         (format "%s\n```%s```\n%s"
                 "Uh-oh! We had an error parsing your response! Maybe this will help:"
                 (pr-str parse-result)
                 "If you can't figure out what went wrong, let me know!"))
        (timbre/error "PARSE ERROR" (:user ctx) s parse-result))
      (try (evaluate ctx parse-result)
           (catch Exception e
             (timbre/error "EVAL ERROR" (:user ctx) e s parse-result))))))

(defn slack-msg [{:keys [db tagtime] :as component} {:keys [params]}]
  (taoensso.timbre/logged-future
   (when (valid-slack? component params)
     (let [user* (slack-user db params)
           user (when user*
                  (assoc
                   user*
                   :macros
                   (into {} (map
                             (juxt :macro/expands-from
                                   #(str/split (:macro/expands-to %) #" "))
                             (db/get-macros db (:id user*))))))]
       (when user
         (let [evt       (:event params)
               channel   (:channel evt)
               me        (first (:authed_users params))
               dm?       (str/starts-with? (or channel "") "D")
               to-me?    (str/starts-with? (:text evt)
                                           (str "<@" me ">"))
               thread-ts (some-> params :event :thread_ts)
               db        (if thread-ts (db/with-last-ping db user thread-ts) db)]
           (timbre/debug [:slack-message
                          {:user    (:id user)
                           :dm?     dm?
                           :to-me?  to-me?
                           :message (get-in params [:event :text])}])
           (when (or dm? to-me? thread-ts)
             (eval-command
              (-> component
                  (assoc :user user)
                  (assoc :db db)
                  (assoc :thread-ts thread-ts)
                  (assoc :channel channel))
              (slack-text params))
             (beeminder/sync! component user)))))))
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

(defn slack-callback [{:keys [config db] :as component} {:keys [params user-id]}]
  (if user-id
    (let [code                   (:code params)
          token-resp             (slack/oauth-access-token (:slack-client-id config)
                                                           (:slack-client-secret config)
                                                           code
                                                           (:slack-redirect-uri config))
          access-token           (token-resp :access-token)
          bot-access-token       (-> token-resp :bot :bot-access-token)
          slack-user-id          (:user-id token-resp)
          {:keys [user]}         (slack/users-info access-token slack-user-id)
          {:keys [channel]}      (slack/im-open bot-access-token slack-user-id)
          {:keys [encrypted iv]} (db/encrypt db bot-access-token)]
      (if (and user-id user bot-access-token channel (:id channel) slack-user-id)
        (do
          (db/create-slack db user-id {:slack/username                   (:name user)
                                       :slack/encrypted-bot-access-token encrypted
                                       :slack/encryption-iv              iv
                                       :slack/dm-id                      (:id channel)
                                       :slack/slack-user-id              slack-user-id})
          (index component))
        UNAUTHORIZED))
    UNAUTHORIZED))

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
       (GET "/editor" _ (index component))
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
       (POST "/slack" _ (partial slack-msg component))
       (context "/api" []
                (GET "/timezones" _ (tictag.resources.timezone/timezones component))
                (GET "/config" _ (tictag.resources.config/config component))
                (ANY "/user" _ (tictag.resources.user/users component))
                (ANY "/user/:id" _ (tictag.resources.user/user component))
                (ANY "/token" _ (tictag.resources.token/token component))
                (ANY "/ping" _ (tictag.resources.ping/pings component))
                (ANY "/ping/:id" _ (tictag.resources.ping/ping component))
                (ANY "/ping-by-ts/:timestamp" _ (tictag.resources.ping/ping-by-ts component))
                (ANY "/macro" _ (tictag.resources.macro/macros component))
                (ANY "/macro/expands-from/:from" _ (tictag.resources.macro/macro-expands-from component))
                (ANY "/macro/:id" _ (tictag.resources.macro/macro component))
                (ANY "/beeminder" _ (tictag.resources.beeminder/beeminders component))
                (ANY "/beeminder/:id" _ (tictag.resources.beeminder/beeminder component))
                (ANY "/slack" _ (tictag.resources.slack/slacks component))
                (ANY "/slack/:id" _ (tictag.resources.slack/slack component))
                (ANY "/goal" _ (tictag.resources.goal/goals component))
                (ANY "/goal/:id" _ (tictag.resources.goal/goal component))))
      (wrap-restful-params :formats [:json-kw :edn :msgpack-kw :yaml-kw :transit-json :transit-msgpack])
      (wrap-defaults (-> api-defaults (assoc :proxy true)))))


(defn my-routes [component]
  (compojure.core/routes
   (site-routes component)
   (api-routes component)))

(defrecord Server [db config tagtime ws jwt]
  component/Lifecycle
  (start [component]
    (debug "Starting server")
    (sente/start-server-chsk-router! (:ch-chsk ws)
                                     (tictag.events/event-msg-handler db jwt))
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

