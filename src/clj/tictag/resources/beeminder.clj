(ns tictag.resources.beeminder
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :refer [id uid params process processable?]]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]))

(defn update! [db uid id beeminder]
  (db/update-beeminder db uid id beeminder))

(defn create! [db uid beeminder]
  (db/create-beeminder db uid beeminder))

(defn location [e]
  (str "/beeminder/" (:beeminder/id e)))

(def beeminder-keys [:beeminder/beeminder :beeminder/tags])

(s/def ::existing-beeminder
  (s/keys :opt [:beeminder/is-enabled?]))

(s/def ::new-beeminder
  (s/keys :req [:beeminder/token]))

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
     (let [[valid? e] (processable? ::existing-beeminder ctx beeminder-keys)]
       [valid? {::changes e}]))
   :put!
   (fn [ctx]
     {::beeminder (update! db (::user-id ctx) (::beeminder-id ctx) (::changes ctx))})
   :new? false
   :respond-with-entity? true
   :handle-ok ::beeminder))

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
     (let [e (process ::new-beeminder ctx beeminder-keys)]
       (if (= e :tictag.resources/unprocessable)
         [false {::not-processable {:beeminder/token "Invalid beeminder token"}}]
         [true {::beeminder e}])))
   :exists?
   (fn [ctx]
     (let [beeminders (db/get-beeminders db (::user-id ctx))]
       {::beeminders beeminders}))
   :post!
   (fn [ctx]
     {:location (location (create! db (::user-id ctx) (::beeminder ctx)))})))
