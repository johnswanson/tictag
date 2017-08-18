(ns tictag.resources.freq
  (:require [tictag.resources.defaults :refer [collection-defaults]]
            [tictag.resources.utils :refer [id uid params processable? process replace-keys]]
            [tictag.db :as db]
            [liberator.core :refer [resource]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as timbre]
            [clj-time.coerce :as tc]
            [clj-time.core :as t]
            [clojure.string :as str]))

(defn freq [{:keys [db]}]
  (resource
   collection-defaults
   :allowed-methods [:get]
   :authorized?
   (fn [ctx]
     (when-let [uid (uid ctx)]
       {::user-id uid}))
   :handle-ok (fn [ctx]
                (db/get-freqs db (::user-id ctx)))))

