(ns tictag.resources.sign
  (:require [tictag.resources.utils :as utils :refer [id uid params processable? process replace-keys query-fn]]
            [liberator.core :refer [resource]]
            [tictag.resources.defaults :refer [collection-defaults resource-defaults]]
            [hiccup.util :refer [url]]
            [tictag.jwt :as jwt]))


(defn sign [{:keys [jwt]}]
  (resource
   resource-defaults
   :allowed-methods [:get]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid
        ::query   (some-> ctx :request :route-params :query utils/b64-decode)}))
   :handle-ok (fn [ctx]
                {:url (str (url "/api/graph" {:sig
                                              (jwt/sign
                                               jwt
                                               {:query   (::query ctx)
                                                :user-id (::user-id ctx)})}))})))
