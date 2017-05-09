(ns tictag.events
  (:require [cljs.tools.reader.edn :as edn]
            [re-frame.core :refer [reg-event-fx reg-event-db reg-fx reg-cofx inject-cofx]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]
            [tictag.nav :as nav]
            [tictag.schemas :as schemas]
            [goog.net.cookies]))

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
    :dispatch-n [[:fetch-pings] [:fetch-user-info]]
    :pushy-navigate :dashboard
    :set-cookie     {:auth-token (:token result)}}))

(reg-event-db
 :login/failed
 [interceptors]
 (fn [db [_ result]]
   ; TODO add handling of failure here
   db))


(reg-event-fx
 :fetch-pings
 [interceptors]
 (fn [{:keys [db]} _]
   {:db         (assoc db :fetching true)
    :http-xhrio (authenticated-xhrio
                 {:method          :get
                  :uri             "/pings"
                  :timeout         8000
                  :response-format (transit-response-format {})
                  :on-success      [:good-pings-result]
                  :on-failure      [:bad-pings-result]}
                 (:auth-token db))}))

(reg-event-fx
 :fetch-user-info
 [interceptors]
 (fn [{:keys [db]} _]
   {:http-xhrio (authenticated-xhrio
                 {:method :get
                  :uri "/api/user/me"
                  :response-format (transit-response-format {})
                  :on-success [:user-me-success]
                  :on-failure [:user-me-failure]}
                 (:auth-token db))}))

(reg-event-db
 :user-me-success
 [interceptors]
 (fn [db [_ user]]
   (-> db
       (assoc :authorized-user user)
       (assoc-in [:settings :temp] user))))

(reg-event-db
 :user-me-failure
 [interceptors]
 (fn [{:keys [db]} _]
   ;; TODO
   db))

(reg-event-db
 :good-pings-result
 [interceptors]
 (fn [db [_ result]]
   (assoc db :pings result)))

(reg-event-db
 :bad-pings-result
 [interceptors]
 (fn [db [_ result]]
   (timbre/errorf "BAD RESULT: %s" (pr-str (keys result)))))

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

(reg-event-db
 :edit-beeminder-token
 [interceptors]
 (fn [db [_ v]]
   (assoc-in db [:settings :temp :beeminder :token] v)))

(reg-event-fx
 :save-beeminder-token
 [interceptors]
 (fn [{:keys [db]} _]
   {:http-xhrio (authenticated-xhrio
                 {:method          :post
                  :uri             "/api/user/me/beeminder"
                  :params          (-> db
                                       (get-in [:settings :temp :beeminder])
                                       (select-keys [:token]))
                  :format          (transit-request-format {})
                  :response-format (transit-request-format {})
                  :on-success      [:beeminder/token-succeed]
                  :on-failure      [:beeminder/token-fail]}
                 (:auth-token db))}))

(reg-event-fx
 :delete-slack
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
    :db (update db :authorized-user dissoc :slack)}))

(reg-event-fx
 :delete-beeminder
 [interceptors]
 (fn [{:keys [db]} _]
   {:http-xhrio (authenticated-xhrio
                 {:method          :delete
                  :uri             "/api/user/me/beeminder"
                  :timeout         8000
                  :format          (transit-request-format {})
                  :response-format (transit-response-format {})
                  :on-success      [:beeminder/delete-succeed]
                  :on-failure      [:beeminder/delete-fail]}
                 (:auth-token db))
    :db (update db :authorized-user dissoc :beeminder)}))

(reg-fx
 :set-cookie
 (fn [kv]
   (doseq [[k v] kv]
     (.set goog.net.cookies (name k) v))))

(reg-fx
 :delete-cookie
 (fn [k]
   (.remove goog.net.cookies (name k))))

(reg-cofx
 :cookie
 (fn [coeffects key]
   (assoc-in coeffects
             [:cookies key]
             (.get goog.net.cookies (name key)))))

(reg-event-fx
 :initialize
 [(inject-cofx :cookie :auth-token) interceptors]
 (fn [{:keys [cookies]} _]
   (merge {:pushy-init true
           :http-xhrio {:method          :get
                        :uri             "/api/timezones"
                        :timeout         8000
                        :format          (transit-request-format {})
                        :response-format (transit-response-format {})
                        :on-success      [:success-timezones]
                        :on-failure      [:failed-timezones]}
           :db {:auth-token (:auth-token cookies)}})))


(reg-event-db
 :success-timezones
 [interceptors]
 (fn [db [_ timezones]]
   (assoc db :allowed-timezones timezones)))

(def logging-out
  {:pushy-replace-token! :login
   :db {:auth-token nil :authorized-user nil}
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
 :save-goal
 [interceptors]
 (fn [{:keys [db]} [_ goal]]
   (let [[errs? params](schemas/validate goal schemas/+goal-schema+)]
     (if-not errs?
       {:db         db
        :http-xhrio (authenticated-xhrio {:method          (if (:id goal) :put :post)
                                          :uri             (str "/api/user/me/goals/" (:id goal))
                                          :params          goal
                                          :timeout         8000
                                          :format          (transit-request-format {})
                                          :response-format (transit-response-format {})
                                          :on-success      [:good-goal-update-result]
                                          :on-failure      [:bad-goal-update-result]}
                                         (:auth-token db))}
       {}))))

(reg-event-db
 :good-goal-update-result
 [interceptors]
 (fn [db [_ goal]]
   db))
