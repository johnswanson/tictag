(ns tictag.events
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.tools.reader.edn :as edn]
            [clojure.string :as str]
            [re-frame.core :refer [reg-event-fx reg-event-db reg-fx reg-cofx inject-cofx]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]
            [ajax.protocols]
            [tictag.nav :as nav]
            [tictag.schemas :as schemas]
            [tictag.dates]
            [tictag.utils :as utils :refer [deep-merge*]]
            [goog.net.cookies]
            [cljs.spec :as s]
            [cljs-time.format :as f]
            [goog.events :as events]
            [goog.net.XhrIo :as xhr]
            [goog.net.EventType]
            [cljs.core.async :as a :refer [<! >! put! chan]]
            [taoensso.sente :as sente :refer [cb-success?]]))

(extend-type goog.net.XhrIo
  ajax.protocols/AjaxImpl
  (-js-ajax-request
    [this
     {:keys [uri method body headers timeout with-credentials
             response-format progress-handler]
      :or {with-credentials false
           timeout 0}}
     handler]
    (when-let [response-type (:type response-format)]
      (.setResponseType this (name response-type)))
   ;; Check for the existence of a :progress-handler arg and register if it's there
    (when progress-handler
      (doto this
        (.setProgressEventsEnabled true)
        (.listen goog.net.EventType.UPLOAD_PROGRESS progress-handler)))
    (doto this
      (events/listen goog.net.EventType/COMPLETE
                     #(handler (.-target %)))
      (.setTimeoutInterval timeout)
      (.setWithCredentials with-credentials)
      (.send uri method body (clj->js headers)))))

(def validate-schema
  (re-frame.core/after (fn [db]
                         (schemas/assert-valid-db! db))))

(def interceptors [(when ^boolean goog.DEBUG re-frame.core/debug)
                   (when ^boolean goog.DEBUG validate-schema)])

(defn authenticated-xhrio [m token]
  (merge m {:headers {"Authorization" token}}))

(reg-event-fx
 :login/submit-login
 [interceptors]
 (fn [{:keys [db]} [_ params]]
   ;; TODO edit DB to say we're pending login and add UI
   {:http-xhrio {:method          :post
                 :uri             "/token"
                 :params          params
                 :timeout         8000
                 :format          (transit-request-format {})
                 :response-format (transit-response-format {})
                 :on-success      [:login/successful]
                 :on-failure      [:login/failed]}}))

(defn allowed-timezones [db]
  (let [tzs (:allowed-timezones db)]
    (set (map :name tzs))))

(reg-event-fx
 :settings/changed-timezone
 [interceptors]
 (fn [{:keys [db]} [_ tz]]
   (if ((allowed-timezones db) tz)
     {:http-xhrio (authenticated-xhrio

                   {:method          :post
                    :params          {:tz tz}
                    :uri             "/api/user/me/tz"
                    :format          (transit-request-format {})
                    :response-format (transit-response-format {})
                    :on-success      [:change-tz-success]
                    :on-failure      [:change-tz-failure]}
                   (:auth-token db))}
     {:db db})))

(reg-event-db
 :change-tz-success
 [interceptors]
 (fn [db [_ {:keys [tz]}]]
   (assoc-in db (conj (:db/authenticated-user db) :tz) tz)))

(reg-event-db
 :change-tz-fail
 [interceptors]
 (fn [db _]
   ;; TODO
   db))

(reg-event-fx
 :login/submit-signup
 [interceptors]
 (fn [{:keys [db]} [_ params]]
   (let [[errs? params] (schemas/validate params
                                          (schemas/+new-user-schema+
                                           (allowed-timezones db)))]
     (if-not errs?
       {:http-xhrio {:method          :post
                     :uri             "/signup"
                     :params          params
                     :timeout         8000
                     :format          (transit-request-format {})
                     :response-format (transit-response-format {})
                     :on-success      [:login/successful]
                     :on-failure      [:login/failed]}}
       {:db (assoc-in db [:signup :errors] errs?)}))))

(reg-event-fx
 :login/successful
 [interceptors]
 (fn [{:keys [db]} [_ result]]
   {:db             (-> db
                        (assoc :auth-token (:token result))
                        (dissoc :signup))
    :dispatch       [:fetch-user-info]
    :pushy-navigate :dashboard
    :set-cookie     {:auth-token (:token result)}}))

(reg-event-db
 :login/failed
 [interceptors]
 (fn [db [_ result]]
   ; TODO add handling of failure here
   db))

(reg-event-fx
 :fetch-user-info
 [interceptors]
 (fn [{:keys [db]} _]
   (when-not (:db/authenticated-user db)
     {:http-xhrio (authenticated-xhrio
                   {:method :get
                    :uri "/api/user/me"
                    :response-format (transit-response-format {})
                    :on-success [:user-me-success]
                    :on-failure [:user-me-failure]}
                   (:auth-token db))})))

(defn ref-to-user [user]
  [:user/by-id (:id user)])

(defn normalize-user [user]
  (-> user
      (dissoc :pings)))

(defn normalize-ping [user-ref ping]
  (assoc ping :user user-ref))

(defn normalize-pings [user-ref pings]
  (into {} (for [p pings]
             [(:timestamp p) (normalize-ping user-ref p)])))

(defn normalize-user-to-db [user]
  {:user/by-id            (when user {(:id user) (normalize-user user)})
   :db/authenticated-user (ref-to-user user)})

(reg-event-fx
 :user-me-success
 [interceptors]
 (fn [{:keys [db]} [_ user]]
   {:db             (merge db (normalize-user-to-db user))
    :sente-connect  (:auth-token db)
    :dispatch-later [{:ms 2 :dispatch [:pings/receive
                                       (ref-to-user user)
                                       true
                                       []
                                       (partition-all 100 (:pings user))]}
                     {:ms 2 :dispatch [:macro/get]}
                     {:ms 2 :dispatch [:beeminder/get]}
                     {:ms 2 :dispatch [:goal/get]}
                     {:ms 2 :dispatch [:slack/get]}]}))


(defn register-query! [n]
  (reg-event-fx
   n
   [interceptors]
   (fn [_ _]
     {:sente-send {:event [n]
                   :timeout 3000
                   :on-success [:db/query-success]
                   :on-failure [:db/query-failure]}})))

(register-query! :goal/get)
(register-query! :beeminder/get)
(register-query! :macro/get)
(register-query! :slack/get)

(reg-event-db
 :db/query-success
 [interceptors]
 (fn [db [_ v]]
   (timbre/debug "merging " v)
   (deep-merge* db v)))

(defn with-path [db path]
  (assoc-in {} path (get-in db path)))

(defn merge-pending [pending saved]
  (let [type (if (seq saved)
               (keyword (namespace (first (keys saved))))
               (keyword (second (str/split (namespace (first (keys pending))) #"pending-"))))]
    (apply merge saved (for [[k v] pending]
                         [(keyword type (name k)) v]))))

(reg-event-fx
 :db/save
 [interceptors]
 (fn [{:keys [db]} [_ type id]]
   (let [path         [type id]
         pending-path (utils/pending-path path)]
     (when (get-in db pending-path)
       {:sente-send {:event      [:db/save
                                  (assoc-in {}
                                            path
                                            (merge-pending
                                             (get-in db pending-path)
                                             (get-in db path)))]
                     :timeout    3000
                     :on-success [:db/save-success type id]
                     :on-failure [:db/save-failure type id]}}))))

(reg-event-db
 :db/save-success
 [interceptors]
 (fn [db [_ type id saved]]
   (let [pending-path (utils/pending-path [type id])
         merged (deep-merge* db saved)]
     (if (:db/errors saved)
       merged
       (update-in merged (butlast pending-path) dissoc (last pending-path))))))

(reg-event-fx
 :db/delete
 [interceptors]
 (fn [{:keys [db]} [_ type id]]
   (let [path [type id]]
     (if (= id :temp)
       {:db (update-in db (butlast (utils/pending-path path)) dissoc :temp)}
       {:sente-send {:event      [:db/save (assoc-in {} path nil)]
                     :timeout    3000
                     :on-success [:db/delete-success type id]
                     :on-failure [:db/delete-failure type id]}}))))

(reg-event-db
 :db/delete-success
 [interceptors]
 (fn [db [_ type id saved]]
   (update db type dissoc id)))

(def formatter (f/formatters :basic-date-time))

(defn process [pings]
  (->> pings
       (map #(assoc % :parsed-time (f/parse formatter (:local-time %))))
       (map #(assoc % :days-since-epoch (tictag.dates/days-since-epoch (:parsed-time %))))
       (map #(assoc % :weeks-since-epoch (tictag.dates/weeks-since-epoch (:parsed-time %))))
       (map #(assoc % :seconds-since-midnight (tictag.dates/seconds-since-midnight (:parsed-time %))))))

(reg-event-fx
 :pings/receive
 (fn [{db :db} [_ user first-time? processed to-process]]
   (if first-time?
     {:dispatch [:pings/receive user false processed to-process]}
     (if-not (seq? to-process)
       {:db (assoc db :pings/by-timestamp (normalize-pings user processed))}
       (let [[n & rest] to-process]
         {:dispatch-later [{:ms 2
                            :dispatch [:pings/receive user false (concat (process n)
                                                                                processed) rest]}]})))))

(reg-event-db
 :user-me-failure
 [interceptors]
 (fn [db _]
   ;; TODO
   db))

(reg-event-fx
 :debounced-update-ping-query
 [interceptors]
 (fn [_ [_ v]]
   {:dispatch-debounce [:upq [:update-ping-query v] 500]}))

(reg-event-db
 :update-ping-query
 [interceptors]
 (fn [db [_ v]]
   (if (seq v)
     (assoc db :ping-query v)
     (assoc db :ping-query nil))))

(reg-fx
 :pushy-init
 (fn [_]
   (nav/start!)))

(reg-event-fx
 :beeminder-token/add
 [interceptors]
 (fn [{:keys [db]} [_ val]]
   {:http-xhrio (authenticated-xhrio
                 {:method          :post
                  :uri             "/api/user/me/beeminder"
                  :params          {:token val}
                  :format          (transit-request-format {})
                  :response-format (transit-response-format {})
                  :on-success      [:beeminder-token/add-succeed]
                  :on-failure      [:beeminder-token/add-fail]}
                 (:auth-token db))
    :db (update-in db
                   [:beeminder/by-id :temp]
                   assoc
                   :token val
                   :user (:db/authenticated-user db))}))

(defn goals [db]
  (let [user (:db/authenticated-user db)]
    (filter #(= (:user %) user) (vals (:goal/by-id db)))))

(reg-fx
 :set-cookie
 (fn [kv]
   (doseq [[k v] kv]
     (.set goog.net.cookies (name k) v (* 60 60 24 365 2)))))

(defonce !chsk (atom nil))
(defonce !ch-chsk (atom nil))
(defonce !chsk-send! (atom nil))
(defonce !chsk-state (atom nil))

(reg-event-db
 :chsk/state
 [interceptors]
 (fn [db [_ v]]
   db))

(reg-event-fx
 :chsk/recv
 [interceptors]
 (fn [_ [_ v]]
   {:dispatch v}))

(reg-event-db
 :chsk/handshake
 [interceptors]
 (fn [db [_ v]]
   db))

(reg-event-db :chsk/ping (fn [db _] db))

(reg-fx
 :sente-connect
 (fn [auth-token]
   (let [{:keys [chsk ch-recv send-fn state]}
         (sente/make-channel-socket! "/chsk"
                                     {:type :auto
                                      :packer :edn
                                      :params {:authorization auth-token}})]
     (go-loop []
       (let [{:keys [event id ?data send-fn]} (<! ch-recv)]
         (re-frame.core/dispatch event)
         (recur)))
     (reset! !chsk chsk)
     (reset! !ch-chsk ch-recv)
     (reset! !chsk-send! send-fn)
     (reset! !chsk-state state))))

(reg-fx
 :sente-send
 (fn [{:keys [event timeout on-success on-failure]}]
   (let [f @!chsk-send!]
     (f event (or timeout 3000) (fn [resp]
                                  (if (cb-success? resp)
                                    (re-frame.core/dispatch (conj on-success resp))
                                    (re-frame.core/dispatch (conj on-failure resp))))))))

(reg-fx
 :delete-cookie
 (fn [k]
   (.remove goog.net.cookies (name k))))


(reg-fx
 :add-window-resize-event-listener
 (fn [_]
   (.addEventListener js/window "resize" #(re-frame.core/dispatch [:window-resize]))))


(reg-cofx
 :cookie
 (fn [coeffects key]
   (assoc-in coeffects
             [:cookies key]
             (.get goog.net.cookies (name key)))))

(reg-cofx
 :window-dimensions
 (fn [coeffects _]
   (assoc coeffects :window-dimensions
          {:width (.-innerWidth js/window)
           :height (.-innerHeight js/window)})))

(reg-event-fx
 :initialize
 [(inject-cofx :cookie :auth-token)
  (inject-cofx :window-dimensions nil)
  interceptors]
 (fn [{:keys [cookies db window-dimensions]} _]
   {:pushy-init                       true
    :http-xhrio                       {:method          :get
                                       :uri             "/api/timezones"
                                       :timeout         8000
                                       :format          (transit-request-format {})
                                       :response-format (transit-response-format {})
                                       :on-success      [:success-timezones]
                                       :on-failure      [:failed-timezones]}
    :dispatch-n                       [[:fetch-user-info]]
    :add-window-resize-event-listener nil
    :db                               {:auth-token (:auth-token cookies)
                                       :db/window  window-dimensions
                                       :macro/by-id {}
                                       :schemas/ui {:pending-macro/by-id {:temp {}}}}}))

(reg-event-fx
 :window-resize
 [(inject-cofx :window-dimensions nil) interceptors]
 (fn [{:keys [db window-dimensions]} _]
   {:db (assoc db :db/window window-dimensions)}))

(reg-event-db
 :success-timezones
 [interceptors]
 (fn [db [_ timezones]]
   (assoc db :allowed-timezones timezones)))

(def logging-out
  {:pushy-replace-token! :login
   :db {:auth-token nil :db/authenticated-user nil}
   :delete-cookie :auth-token})

(def not-logged-in-but-at-auth-page
  {:pushy-replace-token! :login})

(def logged-in-and-at-slack-callback
  {:pushy-replace-token! :dashboard})

(reg-event-fx
 :set-current-page
 [interceptors]
 (fn [{:keys [db]} [_ match]]
   (merge-with
    merge
    {:db (assoc db :nav match)}
    (when (and (= (:handler match) :slack-callback) (:auth-token db))
      logged-in-and-at-slack-callback)
    (when (and (= (:handler match) :dashboard) (not (:auth-token db)))
      not-logged-in-but-at-auth-page)
    (when (and (= (:handler match) :settings) (not (:auth-token db)))
      not-logged-in-but-at-auth-page)
    (when (= (:handler match) :logout)
      logging-out))))


(reg-fx
 :pushy-replace-token!
 (fn [route]
   (nav/replace-token! route)))

(reg-fx
 :pushy-navigate
 (fn [route]
   (nav/set-token! route)))

(reg-event-fx
 :redirect-to-page
 [interceptors]
 (fn [_ [_ page]]
   {:pushy-replace-token! page}))


(reg-event-fx
 :tagtime-import/file
 [interceptors]
 (fn [{:keys [db]} [_ file]]
   {:http-xhrio (authenticated-xhrio
                 {:body             (doto (js/FormData.)
                                      (.append "tagtime-log" file))
                  :progress-handler #(re-frame.core/dispatch
                                      [:tagtime-import/upload-progress (.-name file) %])
                  :method           :put
                  :uri              "/tagtime"
                  :format           (transit-request-format {})
                  :response-format  (transit-response-format {})
                  :on-success       [:tagtime-import/success (.-name file)]
                  :on-failure       [:tagtime-import/fail (.-name file)]}
                 (:auth-token db))
    :db         (assoc-in db [:db/tagtime-upload (.-name file)]
                          {:upload-progress 0})}))

(reg-event-db
 :tagtime-import/upload-progress
 [interceptors]
 (fn [db [_ filename e]]
   (assoc-in db [:db/tagtime-upload filename :upload-progress]
             (* 100 (/ (.-loaded e)
                       (.-total e))))))

(reg-event-db
 :tagtime-import/process-progress
 [interceptors]
 (fn [db [_ {:keys [filename total processed]}]]
   (assoc-in db
             [:db/tagtime-upload filename :process-progress]
             (Math/round (* 100 (/ processed total))))))


(reg-event-db
 :tagtime-import/success
 [interceptors]
 (fn [db [_ filename]]
   (update-in db [:db/tagtime-upload filename]
              merge
              {:upload-progress 100
               :process-progress 0
               :success? true
               :error? []})))

(defn register-update [t]
  (reg-event-db
   (keyword (name t) "update")
   [interceptors]
   (fn [db [_ id k v]]
     (-> db
         (assoc-in [:tictag.schemas/ui
                    (keyword (str "pending-" (name t))
                             "by-id")
                    id
                    (keyword (str "pending-" (name t))
                             (name k))]
                   v)
         (assoc-in [:db/errors
                    (keyword (name t) "by-id")
                    id
                    (keyword (str (name t) (name k)))]
                   nil)))))

(register-update :slack)
(register-update :beeminder)
(register-update :goal)
(register-update :macro)


(reg-event-db
 :tagtime-import/fail
 [interceptors]
 (fn [db [_ filename]]
   (update-in db [:db/tagtime-upload filename]
              merge
              {:error?          [:upload-failed]
               :success?        nil
               :upload-progress 0
               :process-progress 0})))

(defonce timeouts
  (atom {}))

(reg-fx :dispatch-debounce
        (fn [[id event-vec n]]
          (js/clearTimeout (@timeouts id))
          (swap! timeouts assoc id
                 (js/setTimeout (fn []
                                  (re-frame.core/dispatch event-vec)
                                  (swap! timeouts dissoc id))
                                n))))

(reg-fx :stop-debounce
        (fn [id]
          (js/clearTimeout (@timeouts id))
          (swap! timeouts dissoc id)))
