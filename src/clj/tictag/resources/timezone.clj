(ns tictag.resources.timezone
  (:require [liberator.core :refer [resource] :as liberator]
            [tictag.db :as db]))

(defn timezones [component]
  (resource
   :available-media-types ["application/transit+json" "application/json"]
   :allowed-methods [:get]
   :handle-ok (fn [ctx]
                {:timezones (db/timezones (:db component))})))
