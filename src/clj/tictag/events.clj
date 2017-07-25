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

(defmethod -event-msg-handler
  :db/save
  [{:keys [db ?reply-fn ?data id] {:keys [user-id]} :ring-req}]
  (timbre/debug ?data)
  (?reply-fn (db/persist! db user-id ?data)))

(defmethod -event-msg-handler
  :beeminder/get
  [{:keys [db ?reply-fn ?data id] {:keys [user-id]} :ring-req}]
  (timbre/debug id)
  (?reply-fn
   (db/beeminder-client db user-id)))

(defmethod -event-msg-handler
  :goal/get
  [{:keys [db ?reply-fn ?data] {:keys [user-id]} :ring-req}]
  (?reply-fn
   (db/goal-client db user-id)))

(defmethod -event-msg-handler
  :macro/get
  [{:keys [db ?reply-fn] {:keys [user-id]} :ring-req}]
  (timbre/debug :macro/get)
  (?reply-fn
   (db/macro-client db user-id)))

(defmethod -event-msg-handler
  :slack/get
  [{:keys [db ?reply-fn] {:keys [user-id]} :ring-req}]
  (?reply-fn
   (db/slack-client db user-id)))

(defmethod -event-msg-handler
  :ping/get
  [{:keys [db ?reply-fn] {:keys [user-id]} :ring-req}]
  (?reply-fn
   (db/ping-client db user-id)))

(defn event-msg-handler [db jwt]
  (fn [event]
    (-event-msg-handler (assoc event :db db :jwt jwt))))
