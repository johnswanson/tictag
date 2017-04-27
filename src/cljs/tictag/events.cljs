(ns tictag.events
  (:require [cljs.tools.reader.edn :as edn]
            [re-frame.core :refer [reg-event-fx reg-event-db reg-fx]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]
            [tictag.nav :as nav]))

(reg-event-db
 :login/password-input
 (fn [db [_ password]]
   (assoc-in db [:login :password] password)))

(reg-event-db
 :login/username-input
 (fn [db [_ username]]
   (assoc-in db [:login :username] username)))

(reg-event-db
 :login/email-input
 (fn [db [_ email]]
   (assoc-in db [:login :email] email)))

(defn authenticated-xhrio [m token]
  (merge m {:headers {"Authorization" token}}))

(reg-event-fx
 :login/submit-login
 (fn [{:keys [db]} _]
   ;; TODO edit DB to say we're pending login and add UI
   {:http-xhrio {:method          :post
                 :uri             "/token"
                 :params          (:login db)
                 :timeout         8000
                 :format          (transit-request-format {})
                 :response-format (transit-response-format {})
                 :on-success      [:login/successful]
                 :on-failure      [:login/failed]}}))

(reg-event-fx
 :login/submit-signup
 (fn [{:keys [db]} _]
   {:http-xhrio {:method          :post
                 :uri             "/signup"
                 :params          (:login db)
                 :timeout         8000
                 :format          (transit-request-format {})
                 :response-format (transit-response-format {})
                 :on-success      [:login/successful]
                 :on-failure      [:login/failed]}}))

(reg-event-fx
 :login/successful
 (fn [{:keys [db]} [_ result]]
   {:db (-> db
            (assoc :auth-token (:token result))
            (dissoc :login))
    :pushy-navigate :dashboard}))

(reg-event-db
 :login/failed
 (fn [db [_ result]]
   ; TODO add handling of failure here
   db))

(reg-event-fx
 :fetch-pings
 (fn [{:keys [db]} _]
   {:db         (assoc db :fetching true)
    :http-xhrio (authenticated-xhrio
                 {:method          :get
                  :uri             "/pings"
                  :timeout         8000
                  :response-format (transit-response-format {})
                  :on-success      [:good-http-result]
                  :on-failure      [:bad-http-result]}
                 (:auth-token db))}))

(reg-event-db
 :good-http-result
 (fn [db [_ result]]
   (assoc db :pings result)))

(reg-event-db
 :bad-http-result
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

(reg-event-fx
 :initialize
 (fn [_ _]
   {:pushy-init true}))

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
