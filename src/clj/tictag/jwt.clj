(ns tictag.jwt
  (:require [buddy.core.keys :as keys]
            [buddy.sign.jwt :as jwt]
            [taoensso.timbre :as timbre]))

(defn unsign [config token]
  (try (buddy.sign.jwt/unsign token (:public-key config) {:alg :es256})
       (catch Exception _ nil)))

(defn sign [config token]
  (buddy.sign.jwt/sign token (:private-key config) {:alg :es256}))

