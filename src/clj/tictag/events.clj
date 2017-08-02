(ns tictag.events
  (:require [taoensso.timbre :as timbre]
            [tictag.db :as db]
            [tictag.jwt :as jwt]))

(defmulti -event-msg-handler :id)

(defmethod -event-msg-handler
  :default
  [{:keys [id]}]
  (timbre/debug :UNHANDLED-EVENT id))

(defmethod -event-msg-handler :chsk/ws-ping [{}] nil)
(defmethod -event-msg-handler :chsk/uidport-close [{}] nil)
(defmethod -event-msg-handler :chsk/uidport-open [{}] nil)

(defn event-msg-handler [db jwt]
  (fn [event]
    (-event-msg-handler (assoc event :db db :jwt jwt))))
