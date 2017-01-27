(ns tictag.twilio
  (:require [org.httpkit.client :as http]
            [ring.util.request :refer [request-url]]
            [clojure.string :as str]
            [pandect.algo.sha1 :refer [sha1-hmac-bytes]]
            [clojure.data.codec.base64 :as b64]
            [taoensso.timbre :as timbre]))

(def base-url "https://api.twilio.com/2010-04-01/Accounts")
(defn sms-url
  [account-sid]
  (str base-url "/" account-sid "/Messages"))

(defn send-message! [{:keys [account-sid account-token from to]} body]
  (http/request {:url         (sms-url account-sid)
                 :method      :post
                 :form-params {"To"   to
                               "From" from
                               "Body" body}
                 :basic-auth  [account-sid account-token]}))

(defn response [twiml]
  {:status 200
   :headers {"Content-Type" "text/xml"}
   :body twiml})


(defn valid-sig? [twilio req]
  (let [url    (request-url req)
        param-str (str/join (map name (flatten (sort (:params req)))))]
    (= (-> (str url param-str)
        (sha1-hmac-bytes (:account-token twilio))
        (b64/encode)
        (String. "UTF-8"))
       (get-in req [:headers "X-TWILIO-SIGNATURE"]))))
