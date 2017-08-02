(ns tictag.resources.slack
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :refer [id uid params processable? process replace-keys]]
            [tictag.slack :as slack]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]))

(defn update! [db uid id slack]
  (db/update-slack db uid id slack))

(defn create! [db uid slack]
  (db/create-slack db uid slack))

(defn location [e]
  (str "/api/slack/" (:slack/id e)))

(s/def ::existing-slack
  (s/keys :opt [:slack/dm? :slack/channel? :slack/channel-name]))

(defn verify-name [token slack]
  (when-let [name (:slack/channel-name slack)]
    (if-let [ch-id (slack/channel-id token name)]
      (assoc slack :slack/channel-id ch-id)
      :tictag.resources/unprocessable)))

(defn new-in [db slack]
  slack)

(defn existing-in [token]
  [(partial verify-name token) #(replace-keys % {:slack/dm?      :slack/use-dm
                                                 :slack/channel? :slack/use-channel})])

(defn out [db slack]
  (let [t (db/decrypt db
                      (:slack/encrypted-bot-access-token slack)
                      (:slack/encryption-iv slack))]
    (-> slack
        (assoc :slack/token t)
        (dissoc :slack/encrypted-bot-access-token)
        (dissoc :slack/encryption-iv)
        (replace-keys {:slack/use-dm      :slack/dm?
                       :slack/use-channel :slack/channel?}))))

(defn slacks [{:keys [db]}]
  (resource
   collection-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :processable?
   (fn [ctx]
     (let [e (process ::new-slack ctx [:slack/token] (partial new-in db))]
       (if (= e :tictag.resources/unprocessable)
         [false nil]
         [true {::slack e}])))
   :exists?
   (fn [ctx]
     (let [slacks (map (partial out db) (db/get-slacks db (::user-id ctx)))]
       {::slacks slacks}))
   :conflict?
   (fn [ctx]
     (seq (::slacks ctx)))
   :post!
   (fn [ctx]
     {:location (location (create! db (::user-id ctx) (::slack ctx)))})
   :post-redirect? true
   :handle-ok ::slacks))

(defn slack [{:keys [db]}]
  (resource
   resource-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id  uid
        ::slack-id (id ctx)}))
   :can-put-to-missing? false
   :exists?
   (fn [ctx]
     ::slack)
   :delete!
   (fn [ctx]
     (db/delete-slack db (::user-id ctx) (::slack-id ctx)))
   :handle-unprocessable-entity ::unprocessable
   :processable?
   (fn [ctx]
     (let [slack (out db (db/get-slack db (::user-id ctx) (::slack-id ctx)))]
       (if-not slack
         [true nil]
         (let [e     (process ::existing-slack
                              ctx
                              [:slack/dm? :slack/channel-name :slack/channel?]
                              (existing-in (:slack/token slack)))]
           (if (= e :tictag.resources/unprocessable)
             [false {::unprocessable {:slack/channel-name "Slack channel not found"}}]
             [true {::changes e ::slack slack}])))))
   :put!
   (fn [ctx]
     {::slack (out db (update! db (::user-id ctx) (::slack-id ctx) (::changes ctx)))})
   :new? false
   :respond-with-entity? true
   :handle-ok ::slack))
