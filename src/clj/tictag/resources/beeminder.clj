(ns tictag.resources.beeminder
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :refer [id uid params process processable? replace-keys]]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]
            [tictag.beeminder :as beeminder]))

(defn update! [db uid id beeminder]
  (db/update-beeminder db uid id beeminder))

(defn create! [db uid beeminder]
  (db/create-beeminder db uid beeminder))

(defn location [e]
  (str "/api/beeminder/" (:beeminder/id e)))

(s/def ::existing-beeminder
  (s/keys :opt [:beeminder/is-enabled]))

(s/def ::new-beeminder
  (s/keys :req [:beeminder/token]))

(defn new-beeminder [db bm]
  (let [{:keys [beeminder/token]} bm
        user                      (beeminder/user-for token)
        {:keys [encrypted iv]}    (db/encrypt db token)]
    (timbre/debug user)
    (if user
      (-> bm
          (dissoc :beeminder/token)
          (assoc :beeminder/encrypted-token encrypted)
          (assoc :beeminder/encryption-iv iv)
          (assoc :beeminder/is-enabled false)
          (assoc :beeminder/username (:username user)))
      :tictag.resources/unprocessable)))

(defn out [beeminder]
  (-> beeminder
      (dissoc :beeminder/encrypted-token :beeminder/encryption-iv)
      (replace-keys {:beeminder/is-enabled :beeminder/enabled?})))

(defn beeminder [{:keys [db]}]
  (resource
   resource-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid
        ::beeminder-id (id ctx)}))
   :can-put-to-missing? false
   :exists?
   (fn [ctx]
     (when-let [beeminder (db/get-beeminder db (::user-id ctx) (::beeminder-id ctx))]
       {::beeminder beeminder}))
   :conflict?
   (fn [ctx]
     (when-let [beeminder (:beeminder/beeminder (params ctx))]
       (db/get-beeminder db [:and
                        [:= :user-id (::user-id ctx)]
                        [:= :beeminder beeminder]])))
   :delete!
   (fn [ctx]
     (db/delete-beeminder db (::user-id ctx) (::beeminder-id ctx)))
   :processable?
   (fn [ctx]
     (let [e (process ::existing-beeminder ctx [:beeminder/enabled?] [#(replace-keys % {:beeminder/enabled?
                                                                                        :beeminder/is-enabled})])]
       (if (= e :tictag.resources/unprocessable)
         [false {::unprocessable {:beeminder/error "unknown"}}]
         [true {::changes e}])))
   :put!
   (fn [ctx]
     {::beeminder (update! db (::user-id ctx) (::beeminder-id ctx) (::changes ctx))})
   :new? false
   :respond-with-entity? true
   :handle-unprocessable-entity ::unprocessable
   :handle-ok #(out (::beeminder %))))

(defn beeminders [{:keys [db]}]
  (resource
   collection-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :handle-unprocessable-entity ::not-processable
   :processable?
   (fn [ctx]
     (let [e (process ::new-beeminder ctx [:beeminder/token] (partial new-beeminder db))]
       (if (= e :tictag.resources/unprocessable)
         [false {::not-processable {:beeminder/token "Invalid beeminder token"}}]
         [true {::beeminder e}])))
   :exists?
   (fn [ctx]
     (let [beeminders (db/get-beeminders db (::user-id ctx))]
       {::beeminders beeminders}))
   :post!
   (fn [ctx]
     {:location (location (create! db (::user-id ctx) (::beeminder ctx)))})
   :post-redirect? true
   :handle-ok #(map out (::beeminders %))))
