(ns tictag.google
  (:require [oauth.google :refer [oauth-authorization-url
                                  oauth-access-token
                                  oauth-client
                                  *oauth-access-token-url*]]
            [oauth.io :refer [request]]
            [clojure.string :as str]
            [clj-time.core :as t]
            [clj-time.coerce :as coerce]
            [clj-time.format :as f]
            [slingshot.slingshot :refer [try+]]))

(def redirect-uri "urn:ietf:wg:oauth:2.0:oob")

(def scopes
  {:email   "https://www.googleapis.com/auth/userinfo.email"
   :profile "https://www.googleapis.com/auth/userinfo.profile"
   :scope   "https://www.googleapis.com/auth/calendar"})

(defn authorization-url
  [{:keys [client-id]}]
  (oauth-authorization-url client-id redirect-uri :scope (str/join " " (vals scopes))))

(defn client
  [{:keys [client-id client-secret refresh-token]}]
  (oauth-client
   (:access-token
    (request {:method :post
              :url *oauth-access-token-url*
              :form-params {"client_id" client-id
                            "client_secret" client-secret
                            "refresh_token" refresh-token
                            "grant_type" "refresh_token"}}))))

(defn ping-event
  [ping-time tags]
  (let [t         (coerce/from-long ping-time)
        t+1       (t/plus t (t/seconds 1))
        formatter (:date-time f/formatters)]
    {:start   {:dateTime (f/unparse formatter t)}
     :end     {:dateTime (f/unparse formatter t+1)}
     :summary (format "Tags: %s" (str/join "," tags))}))

(defn wrap-insert-event [handler calendar-id]
  (fn [{:keys [timestamp tags] :as req}]
    (handler
     (assoc
      req
      :url (format "https://www.googleapis.com/calendar/v3/calendars/%s/events" calendar-id)
      :method :post
      :content-type :json
      :form-params (ping-event timestamp tags)))))

(defn event-inserter [{:keys [calendar-id] :as config}]
  (wrap-insert-event (client config) calendar-id))


