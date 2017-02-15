(ns tictag.google
  (:require [oauth.google :refer [oauth-authorization-url
                                  oauth-access-token
                                  oauth-client
                                  *oauth-access-token-url*]]
            [oauth.io :refer [request]]
            [clojure.string :as str]
            [clj-time.core :as t]
            [clj-time.local :as l]
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
  {:start   {:dateTime (l/format-local-time ping-time :date-time)}
   :end     {:dateTime (l/format-local-time (t/plus ping-time (t/seconds 1)) :date-time)}
   :summary (format "Tags: %s" (str/join "," tags))})

(defn wrap-insert-event [handler calendar-id]
  (fn [{:keys [ping-time tags] :as req}]
    (handler
     (assoc
      req
      :url (format "https://www.googleapis.com/calendar/v3/calendars/%s/events" calendar-id)
      :method :post
      :content-type :json
      :form-params (ping-event ping-time tags)))))

(defn event-inserter [{:keys [calendar-id] :as config}]
  (wrap-insert-event (client config) calendar-id))


