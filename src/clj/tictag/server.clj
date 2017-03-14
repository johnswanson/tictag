(ns tictag.server
  (:require [re-frame.core :refer [dispatch]]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :refer [GET PUT POST]]
            [taoensso.timbre :as timbre]
            [ring.util.response :refer [response]]
            [ring.middleware.json :refer [wrap-json-params]]
            [ring.middleware.edn :refer [wrap-edn-params]]
            [ring.middleware.transit :refer [wrap-transit-params wrap-transit-response]]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [tictag.db :as db]
            [tictag.cli :refer [parse-body str-number? handle-command]]
            [clojure.string :as str]
            [hiccup.core :refer [html]]
            [hiccup.page :refer [html5]]))

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
  (response (db/get-pings (:db db))))

(defn slack [{:keys [params]}]
  (dispatch [:receive-slack-callback params])
  {:status 200 :body ""})

(defn timestamp [{:keys [params]}]
  (dispatch [:receive-timestamp-req params])
  {:status 200 :body ""})

(defn routes [component]
  (compojure.core/routes
   (POST "/slack" _ slack)
   (PUT "/time/:timestamp" _ timestamp)
   (GET "/pings" _ (pings component))
   (GET "/" _ (index))
   (GET "/config" _ {:headers {"Content-Type" "application/edn"}
                     :status 200
                     :body (pr-str {:tagtime-seed (:seed (:tagtime component))
                                    :tagtime-gap  (:gap (:tagtime component))})})
   (GET "/healthcheck" _ {:status  200
                          :headers {"Content-Type" "text/plain"}
                          :body    "healthy!"})))

(defrecord Server [db config tagtime]
  component/Lifecycle
  (start [component]
    (timbre/debug "Starting server")
    (let [stop (http/run-server
                (-> (routes component)
                    (wrap-transit-response {:encoding :json})
                    (wrap-defaults (assoc-in api-defaults [:static :resources] "/public"))
                    (wrap-edn-params)
                    (wrap-json-params)
                    (wrap-transit-params))
                config)]
      (timbre/debug "Server created")
      (assoc component :stop stop)))
  (stop [component]
    (timbre/debug "Stopping server")
    (when-let [stop (:stop component)]
      (stop))
    (dissoc component :stop)))

