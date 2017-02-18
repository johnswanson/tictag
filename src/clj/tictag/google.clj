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
            [slingshot.slingshot :refer [try+]]
            [com.stuartsierra.component :as component]))

(def redirect-uri "urn:ietf:wg:oauth:2.0:oob")

(def scopes
  {:email   "https://www.googleapis.com/auth/userinfo.email"
   :profile "https://www.googleapis.com/auth/userinfo.profile"
   :scope   "https://www.googleapis.com/auth/calendar"})

(defn authorization-url
  [{:keys [client-id]}]
  (oauth-authorization-url client-id redirect-uri :scope (str/join " " (vals scopes))))

(defn token! [{:keys [client-id client-secret refresh-token]}]
  (:access-token
    (request {:method :post
              :url *oauth-access-token-url*
              :form-params {"client_id" client-id
                            "client_secret" client-secret
                            "refresh_token" refresh-token
                            "grant_type" "refresh_token"}})))

(defn client
  [config]
  (oauth-client (token! config)))

(defn ping-event
  [ping-time tags]
  (let [t         (coerce/from-long ping-time)
        t+1       (t/plus t (t/seconds 1))
        formatter (:date-time f/formatters)]
    {:start   {:dateTime (f/unparse formatter t)}
     :end     {:dateTime (f/unparse formatter t+1)}
     :summary (format "%s" (str/join "," tags))}))

(defrecord EventInserter [db config]
  component/Lifecycle
  (start [component]
    component)
  (stop [component]
    component))

(defn update-event-url [calendar-id event-id]
  (format "https://www.googleapis.com/calendar/v3/calendars/%s/events/%s"
          calendar-id
          event-id))

(defn create-event-url [calendar-id]
  (format "https://www.googleapis.com/calendar/v3/calendars/%s/events"
          calendar-id))

(defn insert-event! [inserter {:keys [timestamp tags calendar-event-id]}]
  (let [new-client  (client (:config inserter))
        calendar-id (:calendar-id (:config inserter))
        base-req
        (if-let [id calendar-event-id]
          {:url    (update-event-url calendar-id id)
           :method :put}
          {:url    (create-event-url calendar-id)
           :method :post})]
    (new-client
     (assoc base-req
            :content-type :json
            :form-params (ping-event timestamp tags)))))

