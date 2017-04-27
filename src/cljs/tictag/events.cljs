(ns tictag.events
  (:require [cljs.tools.reader.edn :as edn]
            [re-frame.core :refer [reg-event-fx reg-event-db]]
            [taoensso.timbre :as timbre]
            [ajax.core :refer [transit-response-format transit-request-format]]))

(reg-event-db
 :login/password-input
 (fn [db [_ password]]
   (assoc-in db [:login :password] password)))

(reg-event-db
 :login/username-input
 (fn [db [_ username]]
   (assoc-in db [:login :username] username)))

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

(reg-event-db
 :login/successful
 (fn [db [_ result]]
   (-> db
       (assoc :auth-token (:token result))
       (dissoc :login))))

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
