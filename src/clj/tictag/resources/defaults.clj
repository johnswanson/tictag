(ns tictag.resources.defaults
  (:require [io.clojure.liberator-transit]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [org.joda.time ReadableInstant DateTime]))

(def joda-time-writer
  (transit/write-handler
   (constantly "m")
   (fn [v] (-> ^ReadableInstant v .getMillis))
   (fn [v] (-> ^ReadableInstant v .getMillis .toString))))

(def liberator-defaults
  {:initialize-context {:liberator-transit {:handlers {DateTime joda-time-writer}}}
   :available-media-types ["application/transit+json" "application/json"]})

(def collection-defaults
  (merge liberator-defaults {:allowed-methods [:get :post]}))

(def resource-defaults
  (merge liberator-defaults {:allowed-methods [:get :put :delete]}))

