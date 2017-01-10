(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [clojure.repl :refer :all]
            [reloaded.repl :refer [system init start stop go reset]]
            [clj-time.core :as t]
            [tictag.server :as server]
            [tictag.client :as client]
            [tictag.config :as config :refer [config]]
            [tictag.db :as db]
            [tictag.beeminder :as bm]
            [tictag.utils :as utils]))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn dev-system []
  (utils/system-map (dissoc (merge server/system (client/system (:server-url config)))
                            :chimer)))

(reloaded.repl/set-init! dev-system)
