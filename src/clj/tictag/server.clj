(ns tictag.server
  (:require [clj-time.format :as f]
            [clj-time.core :as t]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST]]
            [taoensso.timbre :as timbre]
            [ring.util.response :refer [response]]
            [ring.middleware.edn :refer [wrap-edn-params]]
            [ring.middleware.transit :refer [wrap-transit-params wrap-transit-response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [tictag.twilio :as twilio]
            [tictag.db :as db]
            [tictag.cli :refer [parse-body str-number? handle-command]]
            [clojure.string :as str]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]))

(defn handle-timestamp [component params]
  (timbre/debugf "Received client: %s" (pr-str params))
  (let [ping (update params :timestamp str-number?)]
    (assert (db/is-ping? (:db component) (:timestamp ping)))
    (tictag.cli/update-ping component ping)))

(defn index []
  (html5
   [:html {:lang "en"}
    [:head
     [:meta {:charset "utf-8"}]
     [:link {:href "http://fonts.googleapis.com/css?family=Roboto:400,300,200"
             :rel "stylesheet"
             :type "text/css"}]
     [:link {:rel "stylesheet" :href "/css/app.css"}]]
    [:body
     [:div#app]
     [:script {:src "/js/compiled/app.js"}]
     [:script {:src "https://use.fontawesome.com/efa7507d6f.js"}]]]))

(defn pings [{:keys [db]}]
  (timbre/debugf "Received request!")
  (response (db/get-pings (:db db))))

(defn wrap-authenticate [handler f]
  (fn [req]
    (timbre/debugf "Authenticating! (f req) is : %s" (f req))
    (if (f req)
      (handler req)
      {:status 401 :body "unauthorized"})))

(defn sms [component req]
  (handle-command component (get-in req [:params :Body]))
  (twilio/response "<Response></Response>"))

(defn timestamp [component {:keys [params]}]
  (handle-timestamp component params)
  {:status 200 :body ""})

(defn valid-shared-secret? [shared-secret {:keys [params]}]
  (= shared-secret (:secret params)))

(defn verify-valid-sig [{:keys [twilio]} req]
  (twilio/valid-sig? twilio req))

(defn verify-valid-secret [{:keys [config]} {:keys [params]}]
  (= (:shared-secret config)
     (:secret params)))

(defn routes [component]
  (compojure.core/routes
   (POST "/sms" _ (wrap-authenticate
                   (partial sms component)
                   (partial verify-valid-sig component)))
   (PUT "/time/:timestamp" _
     (wrap-authenticate
      (partial timestamp component)
      (partial verify-valid-secret component)))
   (GET "/pings" _ (pings component))
   (GET "/" _ (index))
   (GET "/config" _ {:headers {"Content-Type" "application/edn"}
                     :status 200
                     :body (pr-str {:tagtime-seed (:seed (:tagtime component))
                                    :tagtime-gap  (:gap (:tagtime component))})})
   (GET "/healthcheck" _ {:status  200
                          :headers {"Content-Type" "text/plain"}
                          :body    "healthy!"})))

(defrecord Server [db config tagtime beeminder twilio]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (let [stop (http/run-server
                (-> (routes component)
                    (wrap-transit-response {:encoding :json})
                    (wrap-defaults (assoc-in api-defaults [:static :resources] "/public"))
                    (wrap-edn-params)
                    (wrap-transit-params))
                config)]
      (timbre/debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (timbre/debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

