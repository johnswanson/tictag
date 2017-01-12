(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [reloaded.repl :refer [system init start stop go reset]]
            [tictag.config :as config]
            [tictag.server :as server]
            [tictag.server-api :as api]
            [tictag.client :as client]
            [tictag.client-config :as client-config]
            [tictag.utils :as utils]))

(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src")

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn server-system []
  (utils/system-map server/system))

(defn client-system []
  (utils/system-map (client/system (client-config/remote-url))))

(defn sleepy-pings [& args] (apply (partial api/sleepy-pings (:db system)) args))
(defn sleep [& args] (apply (partial api/sleep (:db system)) args))
(defn add-ping! [& args] (apply (partial api/add-ping! (:db system)) args))
(defn update-ping! [& args] (apply (partial api/update-ping! (:db system)) args))
(defn beeminder-sync! [& args] (apply (partial api/beeminder-sync-from-db! config/beeminder (:db system)) args))
(defn pings [& args] (apply (partial api/pings (:db system)) args))
(defn last-ping [& args] (apply (partial api/last-ping (:db system)) args))

;; first:
;; (reloaded.repl/set-init! server-system)
;; or
;; (reloaded.repl/set-init! client-system)

;; then use (reset) (stop) (start), etc.
