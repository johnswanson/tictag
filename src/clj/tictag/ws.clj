(ns tictag.ws
  (:require [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [taoensso.sente.packers.transit :as sente-transit]
            [cognitect.transit :as transit]
            [compojure.core :refer [GET POST]])
  (:import [org.joda.time DateTime ReadableInstant]))

(def joda-time-writer
  (transit/write-handler
    (constantly "m")
    (fn [v] (-> ^ReadableInstant v .getMillis))
    (fn [v] (-> ^ReadableInstant v .getMillis .toString))))


(def custom-transit-writers {DateTime joda-time-writer})

(def packer (sente-transit/->TransitPacker :json {:handlers custom-transit-writers} {}))

(defrecord Sente []
  component/Lifecycle
  (start [component]
    (let [{:keys [ch-recv send-fn connected-uids
                  ajax-post-fn ajax-get-or-ws-handshake-fn]}
          (sente/make-channel-socket-server! (get-sch-adapter)
                                             {:user-id-fn :user-id
                                              :packer     packer
                                              :handshake-fn (fn [req]
                                                              {:arb :data})})]
      (assoc component
             :ring-ajax-post ajax-post-fn
             :ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn
             :ch-chsk ch-recv
             :chsk-send! send-fn
             :connected-uids connected-uids)))
  (stop [component]
    (dissoc component
            :ring-ajax-post
            :ring-ajax-get-or-ws-handshake
            :ch-chsk
            :chsk-send!
            :connected-uids)))

(defn ws-routes [ws]
  (compojure.core/routes
   (GET "/chsk" _ (:ring-ajax-get-or-ws-handshake ws))
   (POST "/chsk" _ (:ring-ajax-post ws))))
