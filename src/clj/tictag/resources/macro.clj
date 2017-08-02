(ns tictag.resources.macro
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :refer [id uid params processable?]]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]))

(defn create! [db uid macro]
  (db/create-macro db uid macro))

(defn location [e]
  (str "/api/macro/" (:macro/id e)))

(defn update! [db uid id macro]
  (db/update-macro db uid id macro))

(def macro-keys [:macro/expands-from :macro/expands-to])

(s/def ::new-macro
  (s/keys :req [:macro/expands-from :macro/expands-to]))

(s/def ::existing-macro
  (s/keys :opt [:macro/expands-from :macro/expands-to]))

(defn macro [{:keys [db]}]
  (resource
   resource-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id  uid
        ::macro-id (id ctx)}))
   :can-put-to-missing? false
   :exists?
   (fn [ctx]
     (when-let [macro (db/get-macro
                       db
                       (::user-id ctx)
                       (::macro-id ctx))]
       {::macro macro}))
   :conflict?
   (fn [ctx]
     (when-let [expands-from (:macro/expands-from (params ctx))]
       (db/get-macro db [:and
                         [:= :user-id (::user-id ctx)]
                         [:= :expands-from expands-from]])))
   :delete!
   (fn [ctx]
     (db/delete-macro db (::user-id ctx) (::macro-id ctx)))
   :processable?
   (fn [ctx]
     (let [[valid? e] (processable? ::existing-macro ctx macro-keys)]
       [valid? {::changes e}]))
   :put!
   (fn [ctx]
     {::macro (update! db (::user-id ctx) (::macro-id ctx) (::changes ctx))})
   :new? false
   :respond-with-entity? true
   :handle-ok ::macro))

(defn macros [{:keys [db]}]
  (resource
   collection-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :processable?
   (fn [ctx]
     (let [[valid? e] (processable? ::new-macro ctx macro-keys)]
       [valid? {::macro e}]))
   :exists?
   (fn [ctx]
     (let [macros (db/get-macros db (::user-id ctx))]
       {::macros macros}))
   :conflict?
   (fn [ctx]
     (some
      #(= (:macro/expands-from (::macro ctx)) %)
      (map :macro/expands-from (::macros ctx))))
   :post!
   (fn [ctx]
     {:location (location (create! db (::user-id ctx) (::macro ctx)))})
   :post-redirect? true
   :handle-ok ::macros))

