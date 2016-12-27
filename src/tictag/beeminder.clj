(ns tictag.beeminder
  (:require [org.httpkit.client :as http]
            [cheshire.core :as cheshire]))

(defn datapoints-url [user goal]
  (format "https://www.beeminder.com/api/v1/users/%s/goals/%s.json" user goal))

(defn datapoints [creds user goal]
  (:datapoints
   (cheshire/parse-string
    (:body
     @(http/request {:url         (datapoints-url user goal)
                     :method      :get
                     :form-params {:auth_token (:auth-token creds)
                                   :datapoints true}}))
    true)))



