(ns tictag.ragtime
  (:require [ragtime.jdbc :as jdbc]
            [tictag.config]))

(def config
  {:datastore  (jdbc/sql-database (:db tictag.config/config))
   :migrations (jdbc/load-resources "migrations")})

