(ns tictag.jwt
  (:require [buddy.core.keys :as keys]
            [buddy.sign.jwt :as jwt]
            [taoensso.timbre :as timbre]))

(defn unsign [config token]
  (try (buddy.sign.jwt/unsign token (:public-key config) {:alg :es256})
       (catch Exception _ nil)))

(defn sign [config token]
  (buddy.sign.jwt/sign token (:private-key config) {:alg :es256}))

(defn wrap-session-auth [handler jwt]
  (fn [req]
    (if (:user-id req)
      (handler req)
      (let [token (get-in req [:cookies "auth-token" :value])
            user (unsign jwt token)]
        (handler (assoc req :user-id (:user-id user)))))))

(defn wrap-user [handler jwt]
  (fn [req]
    (let [token (get-in req [:headers "authorization"])
          user  (when token (unsign jwt token))]
      (handler (assoc req :user-id (:user-id user))))))
