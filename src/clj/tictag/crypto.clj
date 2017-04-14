(ns tictag.crypto
  (:require [buddy.core.crypto :as crypto]
            [buddy.core.codecs :as codecs]
            [buddy.core.nonce :as nonce]
            [buddy.core.hash :as hash]))

(defn encrypt [string key]
  (let [original (codecs/to-bytes string)
        iv       (nonce/random-bytes 16)]
    {:encrypted (crypto/encrypt original key iv)
     :iv        iv}))

(defn decrypt [bytes key iv]
  (codecs/bytes->str (crypto/decrypt bytes key iv)))
