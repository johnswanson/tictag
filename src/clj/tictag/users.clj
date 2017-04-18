(ns tictag.users
  (:require [tictag.db :as db]
            [tictag.jwt :as jwt]))

(defn get-token [{:keys [db jwt]} username password]
  (if-let [user (db/authenticated-user db username password)]
    [true {:token (jwt/sign jwt {:user-id (:id user)})}]
    [false {:error "Invalid credentials"}]))

