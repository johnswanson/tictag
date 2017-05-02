(ns tictag.events
  (:require [cljs.tools.reader.edn :as edn]
            [re-frame.core :refer [reg-event-fx reg-event-db reg-fx reg-cofx inject-cofx]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]
            [tictag.nav :as nav]
            [tictag.schemas :as schemas]
            [goog.net.cookies]))

(defn authenticated-xhrio [m token]
  (merge m {:headers {"Authorization" token}}))

(reg-event-fx
 :login/submit-login
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
 (fn [{:keys [db]} [_ result]]
   {:db             (-> db
                        (assoc :auth-token (:token result))
                        (dissoc :signup))
    :pushy-navigate :dashboard
    :set-cookie     {:auth-token (:token result)}
    :dispatch-n     [[:fetch-pings] [:fetch-user-info]]}))

(reg-event-db
 :login/failed
 (fn [db [_ result]]
   ; TODO add handling of failure here
   db))

(reg-event-fx
 :logout
 (fn [{:keys [db]} _]
   {:db (dissoc db :auth-token)
    :pushy-navigate :login}))

(reg-event-fx
 :fetch-pings
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
 (fn [db [_ user]]
   (assoc db :authorized-user user)))

(reg-event-db
 :user-me-failure
 (fn [{:keys [db]} _]
   ;; TODO
   db))

(reg-event-db
 :good-pings-result
 (fn [db [_ result]]
   (assoc db :pings result)))

(reg-event-db
 :bad-pings-result
 (fn [db [_ result]]
   (timbre/errorf "BAD RESULT: %s" (pr-str (keys result)))))

(reg-event-db
 :update-ping-query
 (fn [db [_ v]]
   (if (seq v)
     (assoc db :ping-query v)
     (assoc db :ping-query nil))))

(reg-fx
 :pushy-init
 (fn [_]
   (nav/start!)))

(reg-fx
 :set-cookie
 (fn [kv]
   (doseq [[k v] kv]
     (.set goog.net.cookies (name k) v))))

(reg-cofx
 :cookie
 (fn [coeffects key]
   (assoc-in coeffects
             [:cookies key]
             (.get goog.net.cookies (name key)))))

(reg-event-fx
 :initialize
 [(inject-cofx :cookie :auth-token)]
 (fn [{:keys [cookies]} _]
   (let [result {:pushy-init     true
                 :http-xhrio     {:method          :get
                                  :uri             "/api/timezones"
                                  :timeout         8000
                                  :format          (transit-request-format {})
                                  :response-format (transit-response-format {})
                                  :on-success      [:success-timezones]
                                  :on-failure      [:failed-timezones]}
                 :db             {:auth-token (:auth-token cookies)}}]
     (if-let [token (:auth-token cookies)]
       (assoc result :dispatch-later [{:dispatch [:fetch-pings] :ms 100} {:dispatch [:fetch-user-info] :ms 100}])
       result))))

(reg-event-db
 :success-timezones
 (fn [db [_ timezones]]
   (assoc db :allowed-timezones timezones)))

(reg-event-db
 :set-current-page
 (fn [db [_ match]]
   (assoc db :nav match)))

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
 (fn [_ [_ page]]
   {:pushy-replace-token! page}))
