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
  (doseq [k    (keys ?data)
          :let [entity-type (keyword (namespace k))]]
    (doseq [entity-id (keys (?data k))
            :let      [entity (get-in ?data [k entity-id])]]
      (cond

        (nil? entity)
        (when (db/delete! db user-id entity-id entity-type entity)
          (?reply-fn {k {entity-id nil}}))

        (= entity-id :temp)
        (when-let [saved (db/create! db user-id entity-type entity)]
          (?reply-fn {k {(:id saved) saved}}))

        :else
        (when-let [saved (db/update! db user-id entity-id entity-type entity)]
          (?reply-fn {k {(:id saved) saved}}))))))

(defmethod -event-msg-handler
  :macro/get
  [{:keys [db ?reply-fn] {:keys [user-id]} :ring-req}]
  (?reply-fn
   (db/macros db user-id)))

(defn event-msg-handler [db jwt]
  (fn [event]
    (-event-msg-handler (assoc event :db db :jwt jwt))))
