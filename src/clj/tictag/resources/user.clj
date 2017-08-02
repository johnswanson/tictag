(ns tictag.resources.user
  (:require [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [tictag.resources.utils :refer [id uid params processable? process replace-keys]]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]
            [tictag.jwt :as jwt]))

(s/def ::new-user
  (s/keys :req [:user/tz
                :user/username
                :user/email
                :user/pass]))

(s/def ::existing-user
  (s/keys :opt [:user/tz]))

(defn update! [db uid user]
  (db/update-user db uid user))

(defn create! [db user]
  (db/create-user db user))

(defn out [user]
  (select-keys user [:user/id :user/username :user/email :user/tz]))

(def hashp #(buddy.hashers/derive % {:algorithm :bcrypt+blake2b-512}))

(defn hash-password [u]
  (update u :user/pass hashp))

(defn users [{:keys [db jwt]}]
  (resource
   collection-defaults
   :authorized?
   (fn [ctx]
     ;; by definition, we are always authorized to access the user we have the token for.
     ;; this endpoint only allows the creation of a new user (which is authorized for anyone)
     ;; or the fetching of 'my user' (which is determined by the token).
     (case (get-in ctx [:request :request-method])
       :get (when-let [uid (uid ctx)] {::user-id uid})
       :post true))
   :exists?
   (fn [ctx]
     (when (::user-id ctx)
       {::users (map out (db/get-users db (::user-id ctx)))}))
   :handle-ok ::users
   :processable?
   (fn [ctx]
     (let [e (process ::new-user ctx [:user/tz :user/pass :user/username :user/email] [hash-password])]
       (if (= e :tictag.resources/unprocessable)
         [false {::not-processable (s/explain-data ::new-user (params ctx))}]
         [true {::user e}])))
   :handle-unprocessable-entity ::not-processable
   :conflict
   (fn [ctx]
     (let [{:keys [user/username user/email]} (::user ctx)]
       (when (db/get-user-by-username-or-email db username email)
         true)))
   :post!
   (fn [ctx]
     (let [result (create! db (::user ctx))]
       {::user (assoc (out result)
                      :user/auth-token
                      (jwt/sign
                       jwt {:user-id (:user/id result)}))}))
   :handle-created ::user))

(defn user [{:keys [db]}]
  (resource
   resource-defaults
   :authorized?
   (fn [ctx]
     (let [id (id ctx)
           uid (uid ctx)]
       (when (= id uid)
         {::user-id uid})))
   :exists?
   (fn [ctx]
     {::user (out (first (db/get-users db (::user-id ctx))))})
   :handle-ok ::user
   :processable?
   (fn [ctx]
     (let [e (process ::existing-user ctx [:user/tz])]
       (if (= e :tictag.resources/unprocessable)
         [false nil]
         [true {::changes e}])))
   :respond-with-entity? true
   :new? false
   :put!
   (fn [ctx]
     {::user (out (update! db (::user-id ctx) (::changes ctx)))})))
