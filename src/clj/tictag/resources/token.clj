(ns tictag.resources.token
  (:require [tictag.resources.defaults :refer [collection-defaults]]
            [tictag.resources.utils :refer [params]]
            [liberator.core :refer [resource]]
            [taoensso.timbre :as timbre]
            [tictag [db :as db] [jwt :as jwt]]))

(defn get-token [db jwt username password]
  (if-let [user (db/authenticated-user db username password)]
    [true {:token (jwt/sign jwt {:user-id (:id user)})}]
    [false {:error "Invalid credentials"}]))

(defn token [{db :db jwt :jwt}]
  (resource
   collection-defaults
   :authorized? (fn [ctx]
                  (let [{:keys [pending-user/username pending-user/pass]} (params ctx)
                        [valid? t] (get-token
                                    db
                                    jwt
                                    username
                                    pass)]
                    (when valid? {::token t})))
   :post! (fn [ctx] true)
   :handle-created ::token))
