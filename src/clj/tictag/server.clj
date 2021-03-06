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
            [tictag.resources.sign]
            [tictag.resources.freq]
            [tictag.resources.config]
            [tictag.resources.timezone]
            [tictag.resources.user]
            [tictag.resources.macro]
            [tictag.resources.ping]
            [tictag.resources.slack]
            [tictag.resources.graph]
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
     [:link {:rel="stylesheet"
             :href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/4.7.0/css/font-awesome.css"}]
     [:link {:rel "icon"
             :href "/favicon.ico"}]
     [:link {:rel "stylesheet"
             :href "/css/out.css"
             :type "text/css"}]
     [:link {:href "https://fonts.googleapis.com/css?family=Roboto:300,400,500,700,400italic|Robot+Condensed:300,400"
             :rel "stylesheet"
             :type "text/css"}]]
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
     [:link {:rel="stylesheet"
             :href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/4.7.0/css/font-awesome.css"}]
     [:link {:rel "icon"
             :href "/favicon.ico"}]
     [:link {:rel "stylesheet"
             :href "/css/out.css"
             :type "text/css"}]
     [:link {:href "https://fonts.googleapis.com/css?family=Roboto:300,400,500,700,400italic|Robot+Condensed:300,400"
             :rel "stylesheet"
             :type "text/css"}]]
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

(defmethod evaluate :CONT [{:keys [db user] :as ctx} [_ tags]]
  (let [pings (db/sleepy-pings db user)]
    [:SAVE* (map
             (fn [ping]
               [ping (evaluate ctx tags)])
             pings)]))


(defmethod evaluate :CMDS [ctx [_ & vs]]
  (timbre/trace [:evaluate :CMDS vs])
  (doall (map (partial evaluate ctx) vs)))

(defmethod evaluate :ID [{:keys [db user slack]} [_ id-str]]
  (if-let [ping (db/ping-from-id db user id-str)]
    ping
    (do (slack/send-message slack user (format "Warning: can't find ping with id: `%s`" id-str)) nil)))

(defmethod evaluate :LT [{:keys [db user slack]} [_ lt-str]]
  (if-let [ping (db/get-ping db [:and
                                 [:= :user-id (:id user)]
                                 [:= :ts (Long. lt-str)]])]
    ping
    (do (slack/send-message slack user (format "Warning: can't find ping from time: `%s`" lt-str)) nil)))

(defmethod evaluate :DITTO [{:keys [db user]} _]
  [::ditto])

(defmethod evaluate :PLUS [{:keys [db user]} _]
  [::plus])

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
    [:SAVE*
     (map
      (fn [ping]
        [ping ["sleep"]])
      sleepy-pings)]))

(defn tags-before [db user ping]
  (timbre/debug (:ts ping))
  (str/split
   (:tags (let [p (first (db/raw-query db
                                       {:select   [:ts :tags]
                                        :from     [:pings]
                                        :where    [:and
                                                   [:= :user-id (:id user)]
                                                   [:< :ts (:ts ping)]]
                                        :order-by [[:ts :desc]]}))]
            (timbre/debug (:ts p))
            p))
   #" "))

(defn replace-special [db user ping tags]
  (into #{}
        (flatten
         (map (fn [tag]
                (case tag
                  ::plus  (vec (:tags ping))
                  ::ditto (vec (tags-before db user ping))
                  [tag]))
              tags))))

(defn update-pings [db user pings]
  (->> pings
       (map (fn [[p t]]
              (timbre/trace p)
              (when p
                (let [t* (replace-special db user p t)]
                  (assoc p :tags t* :_old-tags (:tags p))))))
       (remove nil?)))

(defn save-pings [{:keys [db user slack]} pings]
  (trace [:save-pings (:id user) pings])
  (db/update-tags! db pings)
  (slack/send-messages
   slack
   user
   (map
    (fn [{:keys [local-time tags _old-tags] :as ping}]
      (timbre/trace "saving ping" ping)
      {:text      (format "updated `%s: %s -> %s`"
                          (f/unparse utils/wtf local-time)
                          _old-tags
                          tags)})
    pings)))

(defn save* [{:keys [db user] :as ctx} ps]
  (trace [:save* (:id user) ps])
  (let [new (update-pings db user ps)]
    (save-pings ctx new)
    {:saved new}))

(defn save [ctx p]
  (save* ctx [p]))

(defn help [{:keys [slack user]}]
  (slack/send-message slack user "TicTag will ping you every 45 minutes, on average.
Pings look like this: `ping <id> [<long-time>]`
Tag the most recent ping (e.g. by saying `ttc`)
Tag a specific ping by responding in a slack thread to that ping's message
Tag a ping by its id (e.g. by saying `113 ttc`)
Tag a ping by its long-time (e.g. by saying `1494519002000 ttc`)
`sleep` command: tag the most recent set of contiguous `afk` pings as `sleep`
`! vacation` will tag the most recent set of contiguous pings as `vacation`
`\"`: macroexpands to the tags of the ping sent *before the one you're tagging*
`+`: macroexpands to the tags of the current ping, so `+foo` will just add the foo tag to existing tags.
Separate commands with a newline to apply multiple commands at once
")
  nil)

(defmethod evaluate :CMD [ctx [_ things]]
  (let [[cmd args] (evaluate ctx things)]
    [cmd
     (case cmd
       :SAVE  (save ctx args)
       :SAVE* (save* ctx args)
       :HELP  (help ctx))]))

(defmethod evaluate :BYID [ctx [_ & args]]
  [:SAVE (map (partial evaluate ctx) args)])

(defmethod evaluate :BYLT [ctx [_ & args]]
  [:SAVE (map (partial evaluate ctx) args)])

(defn valid-slack? [{:keys [config]} params]
  (let [valid? (= (:token params) (:slack-verification-token config))]
    (when (not valid?)
      (warn "INVALID SLACK MESSAGE RECEIVED: %s" params))
    valid?))

(defn eval-command [{:keys [slack user] :as ctx} s]
  (let [parse-result (command-parser s)]
    (if (insta/failure? parse-result)
      (do
        (slack/send-message
         slack
         user
         (format "%s\n```%s```\n%s"
                 "Uh-oh! We had an error parsing your response! Maybe this will help:"
                 (pr-str parse-result)
                 "If you can't figure out what went wrong, let me know!"))
        (timbre/error "PARSE ERROR" user s parse-result))
      (evaluate ctx parse-result))))

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

(defn slack-callback [{:keys [config db slack] :as component} {:keys [params user-id]}]
  (if user-id
    (let [code                   (:code params)
          token-resp             (slack/oauth-access-token (:slack-client-id config)
                                                           (:slack-client-secret config)
                                                           code
                                                           (:slack-redirect-uri config))
          access-token           (token-resp :access-token)
          bot-access-token       (-> token-resp :bot :bot-access-token)
          slack-user-id          (:user-id token-resp)
          {:keys [user]}         (slack/users-info slack access-token slack-user-id)
          {:keys [channel]}      (slack/im-open slack bot-access-token slack-user-id)
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
       (GET "/query" _ (index component))
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
                (ANY "/macro" _ (tictag.resources.macro/macros component))
                (ANY "/macro/expands-from/:from" _ (tictag.resources.macro/macro-expands-from component))
                (ANY "/macro/:id" _ (tictag.resources.macro/macro component))
                (ANY "/beeminder" _ (tictag.resources.beeminder/beeminders component))
                (ANY "/beeminder/:id" _ (tictag.resources.beeminder/beeminder component))
                (ANY "/slack" _ (tictag.resources.slack/slacks component))
                (ANY "/slack/:id" _ (tictag.resources.slack/slack component))
                (ANY "/graph" _ (tictag.resources.graph/graph component))
                (ANY "/freq/:query" _ (tictag.resources.freq/freq component))
                (ANY "/sign/:query" _ (tictag.resources.sign/sign component))
                (ANY "/freq" _ (tictag.resources.freq/freqs component))
                (ANY "/q" _ (tictag.resources.freq/query component))
                (ANY "/goal" _ (tictag.resources.goal/goals component))
                (ANY "/goal/:id" _ (tictag.resources.goal/goal component))))
      (wrap-restful-params :formats [:json-kw :edn :msgpack-kw :yaml-kw :transit-json :transit-msgpack])
      (wrap-defaults (-> api-defaults (assoc :proxy true)))))


(defn my-routes [component]
  (compojure.core/routes
   (site-routes component)
   (api-routes component)))

(defn wrap-method-override [handler]
  (fn [req]
    (if-let [rm (get-in req [:headers "x-http-method-override"])]
      (handler (assoc req :request-method (keyword (str/lower-case rm))))
      (handler req))))

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
                                             :keywordize true}})
                    (wrap-method-override))
                config)]
      (debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))


