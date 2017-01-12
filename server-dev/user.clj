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
            [tictag.utils :as utils]
            [tictag.server-api :as api]))

(clojure.tools.namespace.repl/set-refresh-dirs "server-dev" "src")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn dev-system []
  (utils/system-map server/system))

(reloaded.repl/set-init! dev-system)

(defn sleepy-pings [& args] (apply (partial api/sleepy-pings (:db system)) args))
(defn sleep [& args] (apply (partial api/sleep (:db system)) args))
(defn add-ping! [& args] (apply (partial api/add-ping! (:db system)) args))
(defn update-ping! [& args] (apply (partial api/update-ping! (:db system)) args))
(defn beeminder-sync! [& args] (apply (partial api/beeminder-sync-from-db! config/beeminder (:db system)) args))

(defn pings [& args] (apply (partial api/pings (:db system)) args))

(defn last-ping [& args] (apply (partial api/last-ping (:db system)) args))

