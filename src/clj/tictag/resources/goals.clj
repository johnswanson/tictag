(ns tictag.resources.goal
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :refer [id uid params processable?]]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]))

(defn update! [db uid id goal]
  (db/update-goal db uid id goal))

(defn create! [db uid goal]
  (db/create-goal db uid goal))

(defn location [e]
  (str "/goal/" (:goal/id e)))

(def goal-keys [:goal/goal :goal/tags])

(s/def ::existing-goal
  (s/keys :opt [:goal/goal :goal/tags]))

(s/def ::new-goal
  (s/keys :req [:goal/goal :goal/tags]))

(defn goal [{:keys [db]}]
  (resource
   resource-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid
        ::goal-id (id ctx)}))
   :can-put-to-missing? false
   :exists?
   (fn [ctx]
     (when-let [goal (db/get-goal db (::user-id ctx) (::goal-id ctx))]
       {::goal goal}))
   :conflict?
   (fn [ctx]
     (when-let [goal (:goal/goal (params ctx))]
       (db/get-goal db [:and
                        [:= :user-id (::user-id ctx)]
                        [:= :goal goal]])))
   :delete!
   (fn [ctx]
     (db/delete-goal db (::user-id ctx) (::goal-id ctx)))
   :processable?
   (fn [ctx]
     (let [[valid? e] (processable? ::existing-goal ctx goal-keys)]
       [valid? {::changes e}]))
   :put!
   (fn [ctx]
     {::goal (update! db (::user-id ctx) (::goal-id ctx) (::changes ctx))})
   :new? false
   :respond-with-entity? true
   :handle-ok ::goal))

(defn goals [{:keys [db]}]
  (resource
   collection-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :processable?
   (let [[valid? e] (processable? ::new-goal ctx goal-keys)]
     [valid? {::macro e}])
   :exists?
   (fn [ctx]
     (let [goals (db/get-goals db (::user-id ctx))]
       {::goals goals}))
   :conflict?
   (fn [ctx]
     (some
      #(= (:goal/goal (::goal ctx)) %)
      (map :goal/goal (::goals ctx))))
   :post!
   (fn [ctx]
     {:location (location (create! db (::user-id ctx) (::goal ctx)))})
   :handle-ok ::goals))

