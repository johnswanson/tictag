(ns tictag.resources.config
  (:require [liberator.core :refer [resource]]))


(defn config [{tagtime :tagtime}]
  (resource
   :authorized? (fn [ctx] (get-in ctx [:request :user-id]))
   :available-media-types ["application/transit+json" "application/json" "application/edn"]
   :allowed-methods [:get]
   :handle-ok (fn [ctx]
                {:tagtime-seed (:seed tagtime)
                 :tagtime-gap  (:gap tagtime)})))

