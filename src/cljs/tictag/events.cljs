(ns tictag.events
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.tools.reader.edn :as edn]
            [goog.crypt.base64 :as b64]
            [clojure.string :as str]
            [re-frame.core :refer [reg-event-fx reg-event-db reg-fx reg-cofx inject-cofx]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]
            [taoensso.sente.packers.transit :as sente-transit]
            [ajax.protocols]
            [tictag.nav :as nav]
            [tictag.schemas :as schemas]
            [tictag.dates]
            [tictag.utils :as utils :refer [deep-merge*]]
            [goog.net.cookies]
            [cljs-time.format :as f]
            [cljs-time.core :as t]
            [goog.events :as events]
            [goog.net.XhrIo :as xhr]
            [goog.net.EventType]
            [cljs.core.async :as a :refer [<! >! put! chan]]
            [taoensso.sente :as sente :refer [cb-success?]]))

(defn filters [db]
  (:pie/filters db))
(defn slices [db]
  (:pie/slices db))


(def initial-queries
  [[:ping/get]
   [:freq/get]
   [:user/get]
   [:macro/get]
   [:beeminder/get]
   [:goal/get]
   [:slack/get]
   [:q/get []]])

(declare merge-pending)

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
 :token/create
 [interceptors]
 (fn [{:keys [db]} [_ params]]
   ;; TODO edit DB to say we're pending login and add UI
   {:http-xhrio {:method          :post
                 :uri             "/api/token"
                 :params          (get-in db [:tictag.schemas/ui :pending-user/by-id :temp])
                 :timeout         8000
                 :format          (transit-request-format {})
                 :response-format (transit-response-format {})
                 :on-success      [:login/successful]
                 :on-failure      [:login/failed]}}))

(defn allowed-timezones [db]
  (let [tzs (:allowed-timezones db)]
    (set (map :name tzs))))

(defn user-uri [db]
  (str "/api/user/" (second (:db/authenticated-user db))))

(reg-event-db
 :login/failed
 [interceptors]
 (fn [db [_ v]]
   (assoc-in db [:db/errors :login] :unauthorized)))

(reg-event-fx
 :sente-connected
 [interceptors]
 (fn [{:keys [db]}] db))

(defn register-rest! [t]
  (reg-event-fx
   (keyword (name t) "get")
   [interceptors]
   (fn [{:keys [db]} [_ options]]
     (let [user-id (second (:db/authenticated-user db))]
       {:http-xhrio (authenticated-xhrio
                     {:method          :get
                      :uri             (str "/api/" (name t))
                      :response-format (transit-response-format {})
                      :on-success      [(keyword (name t) "get-success")]
                      :on-failure      [(keyword (name t) "get-failure")]}
                     (:auth-token db))})))
  (reg-event-fx
   (keyword (name t) "save")
   [interceptors]
   (fn [{:keys [db]} [_ id]]
     (let [path         [(keyword (name t) "by-id") id]
           pending-path (utils/pending-path path)]
       (when (get-in db pending-path)
         {:http-xhrio (authenticated-xhrio
                       {:method          (if (= id :temp) :post :put)
                        :uri             (if (= id :temp)
                                           (str "/api/" (name t))
                                           (str "/api/" (name t) "/" id))
                        :response-format (transit-response-format {})
                        :format          (transit-request-format {})
                        :params          (merge-pending
                                          (get-in db pending-path)
                                          (get-in db path))
                        :on-success      [(keyword (name t) "save-success") id]
                        :on-failure      [(keyword (name t) "save-failure") id]}
                       (:auth-token db))}))))
  (reg-event-fx
   (keyword (name t) "delete")
   [interceptors]
   (fn [{:keys [db]} [_ id]]
     (if (= id :temp)
       {:db (assoc-in db (utils/pending-path [(keyword (name t) "by-id") id]) {})}
       {:http-xhrio (authenticated-xhrio
                     {:method :delete
                      :uri (str "/api/" (name t) "/" id)
                      :response-format (transit-response-format {})
                      :format (transit-request-format {})
                      :on-success [(keyword (name t) "delete-success")]
                      :on-failure [(keyword (name t) "delete-failure")]}
                     (:auth-token db))})))
  (reg-event-fx
   (keyword (name t) "save-success")
   [interceptors]
   (fn [{:keys [db]} [_ id v]]
     {:db (-> db
              (assoc-in [(keyword (name t) "by-id") (get v (keyword (name t) "id"))] v)
              (assoc-in [:tictag.schemas/ui (keyword (str "pending-" (name t)) "by-id") :temp] {}))
      :dispatch [(keyword (name t) "save-success-custom") v]}))
  (reg-event-db
   (keyword (name t) "save-failure")
   [interceptors]
   (fn [db [_ id v]]
     (timbre/debug (:response v))
     (assoc-in db
               [:db/errors (keyword (name t) "by-id") id]
               (:response v))))
  (reg-event-db
   (keyword (name t) "delete-success")
   [interceptors]
   (fn [db [_ v]]
     (-> db
         (update (keyword (name t) "by-id") dissoc ((keyword (name t) "id") v))))))

(register-rest! :ping)
(register-rest! :macro)
(register-rest! :goal)
(register-rest! :beeminder)
(register-rest! :slack)
(register-rest! :user)
(register-rest! :freq)

(reg-event-db
 :beeminder/get-success
 [interceptors]
 (fn [db [_ v]]
   (assoc db :beeminder/by-id (into {} (map (juxt :beeminder/id identity) v)))))

(reg-event-db
 :goal/get-success
 [interceptors]
 (fn [db [_ v]]
   (assoc db :goal/by-id (into {} (map (juxt :goal/id identity) v)))))

(reg-event-db
 :slack/get-success
 [interceptors]
 (fn [db [_ v]]
   (assoc db :slack/by-id (into {} (map (juxt :slack/id identity) v)))))

(reg-event-db
 :macro/get-success
 [interceptors]
 (fn [db [_ v]]
   (assoc db :macro/by-id (into {} (map (juxt :macro/id identity) v)))))

(reg-event-fx
 :ping/get-failure
 [interceptors]
 (fn [_ evt] nil))

(reg-event-db
 :ping/get-success
 [interceptors]
 (fn [db [_ v]]
   (assoc db
          :ping/sorted-ids (map :ping/id v)
          :ping/by-id (into {} (map (juxt :ping/id identity) v)))))

(reg-event-db
 :freq/get-success
 [interceptors]
 (fn [db [_ v]]
   (assoc db
          :freq/by-tag (into {} (map (juxt :freq/tag :freq/count) v))
          :freq/sorted-tags (map :freq/tag v))))

(reg-event-db
 :user/get-success
 [interceptors]
 (fn [db [_ v]]
   (-> db
       (assoc :user/by-id (into {} (map (juxt :user/id identity) v)))
       (assoc :db/authenticated-user [:user/by-id (:user/id (first v))]))))

(reg-event-fx
 :login/successful
 [interceptors]
 (fn [{:keys [db]} [_ v]]
   (when-let [auth-token (:token v)]
     {:db             (-> db
                          (assoc :auth-token auth-token))
      :sente-connect  auth-token
      :dispatch-n     initial-queries
      :pushy-navigate :dashboard
      :set-cookie     {:auth-token auth-token}})))

(reg-event-fx
 :user/save-success-custom
 [interceptors]
 (fn [{:keys [db]} [_ v]]
   (when-let [auth-token (:user/auth-token v)]
     {:db             (-> db
                          (assoc :auth-token auth-token)
                          (assoc :db/authenticated-user [:user/by-id (:user/id v)]))
      :sente-connect  auth-token
      :dispatch-n     initial-queries
      :pushy-navigate :dashboard
      :set-cookie     {:auth-token auth-token
                       :user-id    (:user/id v)}})))

(defn with-path [db path]
  (assoc-in {} path (get-in db path)))

(defn merge-pending [pending saved]
  (let [type (if (seq saved)
               (keyword (namespace (first (keys saved))))
               (keyword (second (str/split (namespace (first (keys pending))) #"pending-"))))]
    (apply merge saved (for [[k v] pending]
                         [(keyword type (name k)) v]))))

(def formatter (f/formatters :basic-date-time))

(reg-event-fx
 :debounced-slack/update
 [interceptors]
 (fn [_ v]
   {:dispatch-debounce [:dsu (assoc v 0 :slack/update)]}))

(reg-fx
 :pushy-init
 (fn [_]
   (nav/start!)))

(reg-fx
 :set-cookie
 (fn [kv]
   (doseq [[k v] kv]
     (.set goog.net.cookies (name k) v (* 60 60 24 365 2)))))

(defonce !chsk (atom nil))
(defonce !ch-chsk (atom nil))
(defonce !chsk-send! (atom nil))
(defonce !chsk-state (atom nil))

(reg-event-fx
 :chsk/state
 [interceptors]
 (fn [{:keys [db]} [_ [old new]]]
   (when (:first-open? new)
     )))

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
                                      :packer (sente-transit/get-transit-packer)
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
 (fn [ks]
   (doseq [k ks]
     (.remove goog.net.cookies (name k)))))


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
  (inject-cofx :cookie :user-id)
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
    :sente-connect                    (:auth-token cookies)
    :dispatch-n                       initial-queries
    :add-window-resize-event-listener nil
    :db                               (let [db {:auth-token   (:auth-token cookies)
                                                :pie/slices []
                                                :db/window    window-dimensions
                                                :macro/by-id  {}
                                                :schemas/ui   {:pending-user/by-id {:temp {:pending-user/tz (utils/local-tz)}}
                                                               :pending-macro/by-id {:temp {}}}}]
                                        (if-let [uid (some-> cookies :user-id js/parseInt)]
                                          (assoc db :db/authenticated-user [:user/by-id uid])
                                          db))}))

(reg-event-fx
 :window-resize
 [(inject-cofx :window-dimensions nil) interceptors]
 (fn [{:keys [db window-dimensions]} _]
   {:db (assoc db :db/window window-dimensions)}))

(reg-event-db
 :success-timezones
 [interceptors]
 (fn [db [_ timezones]]
   (assoc db :allowed-timezones (:timezones timezones))))

(def logging-out
  {:pushy-replace-token! :login
   :db {:auth-token nil :db/authenticated-user nil}
   :delete-cookie [:auth-token :user-id]})

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

(defn update-db [t db id k v]
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
                 (keyword (name t) (name k))]
                nil)))

(defn register-update [t]
  (reg-event-db
   (keyword (name t) "update")
   [interceptors]
   (fn [db [_ id k v]]
     (update-db t db id k v))))

(reg-event-db
 :user/update
 [interceptors]
 (fn [db [_ id k v]]
   (-> db
       (assoc-in [:tictag.schemas/ui
                  :pending-user/by-id
                  id
                  (keyword (str "pending-user" (name k)))]
                 v)
       (assoc-in [:db/errors :login] nil))))

(reg-event-db
 :ping/update
 [interceptors]
 (fn [db [_ id k v]]
   (if (= k :ping/tags)
     (let [tags     (str/split (str/lower-case v) #" ")
           macros   (into {} (map (juxt :macro/expands-from :macro/expands-to) (vals (:macro/by-id db))))
           new-tags (str/join " " (map #(get macros % %) tags))]
       (update-db :ping db id k new-tags))
     (update-db :ping db id k v))))

(register-update :slack)
(register-update :beeminder)
(register-update :goal)
(register-update :macro)
(register-update :user)

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

(reg-event-fx
 :pie/get*
 [interceptors]
 (fn [{:keys [db]} _]
   {:http-xhrio {:method          :post
                 :uri             "/api/q"
                 :headers         {"x-http-method-override" "GET"
                                   "Authorization"          (:auth-token db)}
                 :params          {:slices  (slices db)
                                   :filters (filters db)}
                 :format          (transit-request-format {})
                 :response-format (transit-response-format {})
                 :on-success      [:pie/get-success]
                 :on-failure      [:pie/get-failure]}}))



(reg-event-fx
 :pie/get
 [interceptors]
 (fn [_ [_ v]]
   {:dispatch-debounce [:pieget [:pie/get*] 500]}))



(reg-event-db
 :pie/get-success
 [interceptors]
 (fn [db [_ v]]
   (assoc db :pie/results v)))

(reg-event-fx
 :pie/add-slice
 [interceptors]
 (fn [{:keys [db]} [_ v]]
   {:db (update-in db [:pie/slices] (fnil conj []) v)
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie/update-slice
 [interceptors]
 (fn [{:keys [db]} [_ idx v]]
   {:db (update-in db [:pie/slices] (fnil assoc []) idx v)
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie/delete-slice
 [interceptors]
 (fn [{:keys [db]} [_ idx]]
   {:dispatch [:pie/get]
    :db (update-in db [:pie/slices] (fn [slices]
                                      (vec (concat (subvec slices 0 idx)
                                                   (subvec slices (inc idx))))))}))

(reg-event-fx
 :pie-filters/change-query
 [interceptors]
 (fn [{:keys [db]} [_ v]]
   (let [v (if (seq v) v nil)]
     {:db (assoc-in db [:pie/filters :query] v)
      :dispatch [:pie/get]})))

(reg-event-fx
 :pie-filters/day-select
 [interceptors]
 (fn [{:keys [db]} [_ day val]]
   {:db (if val
          (update-in db [:pie/filters :days] (fnil conj #{}) day)
          (update-in db [:pie/filters :days] (fnil disj #{}) day))
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/day-select-all
 [interceptors]
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:pie/filters :days] #{:sun :mon :tue :wed :thu :fri :sat})
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/day-select-none
 [interceptors]
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:pie/filters :days] #{})
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/day-select-weekdays-only
 [interceptors]
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:pie/filters :days] #{:mon :tue :wed :thu :fri})
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/day-select-weekends-only
 [interceptors]
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:pie/filters :days] #{:sat :sun})
    :dispatch [:pie/get]}))

(reg-cofx
 :today
 (fn [coeffects _]
   (assoc coeffects :today (t/today))))

(reg-event-fx
 :pie-filters/select-today
 [(inject-cofx :today)
  interceptors]
 (fn [{:keys [db today]} _]
   {:db (-> db
            (assoc-in [:pie/filters :start-date] (f/unparse (f/formatter "YYYY-MM-dd") today))
            (assoc-in [:pie/filters :end-date] nil))
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/select-last-week
 [(inject-cofx :today)
  interceptors]
 (fn [{:keys [db today]} _]
   {:db (-> db
            (assoc-in [:pie/filters :start-date] (f/unparse (f/formatter "YYYY-MM-dd") (t/plus today (t/days -7))))
            (assoc-in [:pie/filters :end-date] nil))
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/select-last-month
 [(inject-cofx :today)
  interceptors]
 (fn [{:keys [db today]} _]
   {:db (-> db
            (assoc-in [:pie/filters :start-date] (f/unparse (f/formatter "YYYY-MM-dd") (t/plus today (t/months -1))))
            (assoc-in [:pie/filters :end-date] nil))
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/select-last-year
 [(inject-cofx :today)
  interceptors]
 (fn [{:keys [db today]} _]
   {:db (-> db
            (assoc-in [:pie/filters :start-date] (f/unparse (f/formatter "YYYY-MM-dd") (t/plus today (t/years -1))))
            (assoc-in [:pie/filters :end-date] nil))
    :dispatch [:pie/get]}))

(reg-event-fx
 :pie-filters/select-all-dates
 [interceptors]
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:pie/filters :start-date] nil)
            (assoc-in [:pie/filters :end-date] nil))
    :dispatch [:pie/get]}))

(reg-event-db
 :pie-filters/set-start-date
 [interceptors]
 (fn [{:keys [db]} [_ v]]
   {:db (assoc-in db [:pie/filters :start-date] v)
    :dispatch [:pie/get]}))

