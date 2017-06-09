(ns tictag.events
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [cljs.tools.reader.edn :as edn]
            [re-frame.core :refer [reg-event-fx reg-event-db reg-fx reg-cofx inject-cofx]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]
            [ajax.protocols]
            [tictag.nav :as nav]
            [tictag.schemas :as schemas]
            [tictag.dates]
            [tictag.utils :refer [descend]]
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


(def interceptors [(when ^boolean goog.DEBUG re-frame.core/debug)])

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

(defn ref-to-goal [goal]
  [:goal/by-id (:goal/id goal)])

(defn ref-to-beeminder [beeminder]
  [:beeminder/by-id (:id beeminder)])

(defn ref-to-slack [slack]
  [:slack/by-id (:id slack)])

(defn ref-to-user [user]
  [:user/by-id (:id user)])

(defn normalize-goal [user beeminder goal]
  (-> goal
      (assoc :beeminder (ref-to-beeminder beeminder))
      (assoc :user (ref-to-user user))))

(defn normalize-beeminder [user beeminder]
  (-> beeminder
      (dissoc :goals)
      (assoc :user (ref-to-user user))))

(defn normalize-slack [user slack]
  (-> slack
      (assoc :user (ref-to-user user))))

(defn normalize-user [user]
  (-> user
      (dissoc :beeminder)
      (dissoc :slack)
      (dissoc :pings)))

(defn normalize-goals [user beeminder goals]
  (into {} (for [g goals]
             [(:goal/id g) (normalize-goal user beeminder g)])))

(defn normalize-ping [user-ref ping]
  (assoc ping :user user-ref))

(defn normalize-pings [user-ref pings]
  (into {} (for [p pings]
             [(:timestamp p) (normalize-ping user-ref p)])))

(defn normalize-user-to-db [user]
  (let [slack                (:slack user)
        beeminder            (:beeminder user)
        normalized-slack     (normalize-slack user slack)
        normalized-beeminder (normalize-beeminder user beeminder)
        normalized-goals     (normalize-goals user beeminder (:goals beeminder))]
    {:slack/by-id           (when slack {(:id slack) (normalize-slack user slack)})
     :beeminder/by-id       (when beeminder {(:id beeminder) (normalize-beeminder user beeminder)})
     :user/by-id            (when user {(:id user) (normalize-user user)})
     :goal/by-id            (normalize-goals user beeminder (get-in user [:beeminder :goals]))
     :db/authenticated-user (ref-to-user user)}))

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
                                       (partition-all 100 (:pings user))]}]}))

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

(reg-event-fx
 :beeminder/enable?
 [interceptors]
 (fn [{:keys [db]} [_ enable?]]
   (let [user      (:db/authenticated-user db)
         beeminder [:beeminder/by-id
                    (:id
                     (first (filter #(= user (:user %))
                                    (vals (:beeminder/by-id db)))))]]
     {:http-xhrio (authenticated-xhrio
                   {:method          :post
                    :uri             "/api/user/me/beeminder/enable"
                    :params          {:enable? enable?}
                    :format          (transit-request-format {})
                    :response-format (transit-response-format {})
                    :on-success      [:beeminder/enable-succeed]
                    :on-failure      [:beeminder/enable-fail]}
                   (:auth-token db))
      :db (assoc-in db (conj beeminder :enabled?) enable?)})))

(reg-event-db
 :beeminder/enable-succeed
 [interceptors]
 (fn [db _] db))

(reg-event-db
 :beeminder/enable-fail
 [interceptors]
 (fn [db _] db))

(reg-event-db
 :beeminder-token/add-fail
 [interceptors]
 (fn [db [_ val]]
   (assoc-in db [:beeminder/by-id :temp] nil)))

(reg-event-db
 :beeminder-token/add-succeed
 [interceptors]
 (fn [db [_ val]]
   (let [{:keys [id]} val]
     (-> db
         (update-in [:beeminder/by-id :temp] dissoc :user)
         (assoc-in [:beeminder/by-id id] (assoc val :user (:db/authenticated-user db)))))))

(reg-event-fx
 :slack/delete
 [interceptors]
 (fn [{:keys [db]} _]
   {:http-xhrio (authenticated-xhrio
                 {:method          :delete
                  :uri             "/api/user/me/slack"
                  :timeout         8000
                  :format          (transit-request-format {})
                  :response-format (transit-response-format {})
                  :on-success      [:slack/delete-succeed]
                  :on-failure      [:slack/delete-fail]}
                 (:auth-token db))
    :db (assoc db :slack/by-id nil)}))

(defn goals [db]
  (let [user (:db/authenticated-user db)]
    (filter #(= (:user %) user) (vals (:goal/by-id db)))))

(reg-event-fx
 :beeminder-token/delete
 [interceptors]
 (fn [{:keys [db]} [_ path]]
   (if-not (seq (goals db))
     {:http-xhrio (authenticated-xhrio
                   {:method          :delete
                    :uri             "/api/user/me/beeminder"
                    :timeout         8000
                    :format          (transit-request-format {})
                    :response-format (transit-response-format {})
                    :on-success      [:beeminder-token/delete-succeed]
                    :on-failure      [:beeminder-token/delete-fail path]}
                   (:auth-token db))
      :db (assoc db :beeminder/by-id nil)}
     {})))

(reg-event-db
 :beeminder-token/delete-fail
 [interceptors]
 (fn [db [_ path val]]
   (assoc-in db (conj (:db/authenticated-user db) :beeminder) path)))

(reg-fx
 :set-cookie
 (fn [kv]
   (doseq [[k v] kv]
     (.set goog.net.cookies (name k) v))))

(defonce !chsk (atom nil))
(defonce !ch-chsk (atom nil))
(defonce !chsk-send! (atom nil))
(defonce !chsk-state (atom nil))

(reg-event-db
 :chsk/state
 [interceptors]
 (fn [db [_ v]]
   (js/console.log v)
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
   (js/console.log v)
   db))

(reg-fx
 :sente-connect
 (fn [auth-token]
   (js/console.log auth-token)
   (let [{:keys [chsk ch-recv send-fn state]}
         (sente/make-channel-socket! "/chsk"
                                     {:type :auto
                                      :packer :edn})]
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
 (fn [event]
   (let [f @!chsk-send!]
     (f event))))

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
   (merge {:pushy-init                       true
           :http-xhrio                       {:method          :get
                                              :uri             "/api/timezones"
                                              :timeout         8000
                                              :format          (transit-request-format {})
                                              :response-format (transit-response-format {})
                                              :on-success      [:success-timezones]
                                              :on-failure      [:failed-timezones]}
           :dispatch-n                       [[:fetch-user-info]]
           :add-window-resize-event-listener nil
           :db                               (merge db {:auth-token (:auth-token cookies)
                                                        :db/window  window-dimensions})})))

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

(reg-event-db
 :goal/new
 [interceptors]
 (fn [db _]
   (let [user      (:db/authenticated-user db)
         beeminder [:beeminder/by-id
                    (:id
                     (first (filter #(= user (:user %))
                                    (vals (:beeminder/by-id db)))))]]
     (assoc-in db
               [:goal/by-id :temp]
               {:user user
                :beeminder beeminder
                :goal/id :temp}))))

(reg-event-db
 :goal/edit
 [interceptors]
 (fn [db [_ id k new]]
   (assoc-in db [:goal/by-id id k] new)))

(reg-event-fx
 :goal/delete
 [interceptors]
 (fn [{:keys [db]} [_ id]]
   (merge
    {:db (let [old (get-in db [:goal/by-id id])]
           (-> db
               (assoc-in [:goal/by-id id] nil)
               (assoc-in [:db/trash :goal/by-id id] old)))}
    (when (not= :temp id)
      {:http-xhrio (authenticated-xhrio
                    {:uri             (str "/api/user/me/goals/" id)
                     :method          :delete
                     :timeout         8000
                     :format          (transit-request-format {})
                     :response-format (transit-response-format {})
                     :on-success      [:goal/delete-succeed]
                     :on-failure      [:goal/delete-fail id]}
                    (:auth-token db))}))))

(reg-event-db
 :goal/delete-fail
 [interceptors]
 (fn [db [_ id result]]
   (let [old (get-in db [:db/trash :goal/by-id id])]
     (assoc-in db [:goal/by-id id] old))))

(reg-event-fx
 :goal/save
 [interceptors]
 (fn [{:keys [db]} [_ id]]
   (let [goal (get-in db [:goal/by-id id] {})
         base (if (= :temp id)
                {:method :post
                 :uri "/api/user/me/goals/"}
                {:method :put
                 :uri (str "/api/user/me/goals/" id)})]
     (if (and (s/valid? :tictag.schemas/goal goal)
              (= (count (filter #(= (:goal/name goal)
                                    (:goal/name %))
                                (vals (:goal/by-id db))))
                 1))
       {:http-xhrio (authenticated-xhrio
                     (merge {:params          goal
                             :timeout         8000
                             :format          (transit-request-format {})
                             :response-format (transit-response-format {})
                             :on-success      [:good-goal-update-result id]
                             :on-failure      [:bad-goal-update-result id]}
                            base)
                     (:auth-token db))}
       {}))))

(reg-event-db
 :good-goal-update-result
 [interceptors]
 (fn [db [_ old-id to-merge]]
   (if (= old-id :temp)
     (-> db
         (update-in (conj (:beeminder to-merge) :goals)
                    conj [:goal/by-id (:goal/id to-merge)])
         (assoc-in [:goal/by-id :temp] nil)
         (assoc-in [:goal/by-id (:goal/id to-merge)] to-merge))
     (assoc-in db [:goal/by-id (:goal/id to-merge)] to-merge))))

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
