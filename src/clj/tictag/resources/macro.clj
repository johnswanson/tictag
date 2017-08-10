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

(s/def :macro/expands-from (s/and string? #(re-matches #"^[-:.\p{L}][-:.\p{L}0-9]*$" %)))
(s/def :macro/expands-to (s/and string? #(re-matches #"^[-:.\p{L}][-:.\p{L}0-9 ]*$" %)))

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
                         [:= :expands-from expands-from]
                         [:not= :id (::macro-id ctx)]])))
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

(defn macro-expands-from [{:keys [db]}]
  (resource
   resource-defaults
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid
        ::from    (some-> ctx :request :route-params :from)}))
   :can-put-to-missing? true
   :exists? ::macro
   :delete!
   (fn [ctx]
     (when (::macro ctx)
       (db/delete-macro db [:and
                            [:= :user-id (::user-id ctx)]
                            [:= :expands-from (::from ctx)]])))
   :processable?
   (fn [ctx]
     (let [old-macro  (db/get-macro
                       db
                       [:and
                        [:= :user-id (::user-id ctx)]
                        [:= :expands-from (::from ctx)]])
           expands-to (:macro/expands-to (params ctx))
           new-macro  {:macro/expands-to   (:macro/expands-to (params ctx))
                       :macro/expands-from (::from ctx)}]
       (if expands-to
         {::changes   new-macro
          ::macro     old-macro
          ::old-macro old-macro}
         [false {::unprocessable "Must specify :macro/expands-to"}])))
   :handle-unprocessable-entity ::unprocessable
   :put!
   (fn [ctx]
     (if (::macro ctx)
       {::macro (db/update-macro db
                                 [:and
                                  [:= :user-id (::user-id ctx)]
                                  [:= :expands-from (::from ctx)]]
                                 (::changes ctx))}
       {::macro (db/create-macro db (::user-id ctx) (::changes ctx))}))
   :new? #(not (::old-macro %))
   :respond-with-entity? true
   :handle-created ::macro
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

