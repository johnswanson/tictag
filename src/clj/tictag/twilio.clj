(ns tictag.twilio
  (:require [org.httpkit.client :as http]))

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
