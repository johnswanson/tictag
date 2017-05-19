(ns tictag.riemann
  (:require [riemann.client :as r]
            [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [chime :refer [chime-at]]
            [clj-time.core :as t]
            [clj-time.periodic :refer [periodic-seq]]
            [tictag.db :as db]
            [taoensso.timbre :as timbre]))

(defn send! [c e]
  (timbre/debug e)
  (r/send-event (:conn c) e))

(defrecord RiemannClient [config db]
  component/Lifecycle
  (start [component]
    (let [times  (rest (periodic-seq (t/now) (-> 55 t/seconds)))
          conn   (r/tcp-client config)
          chimer (chime-at times
                           (fn [time]
                             (if (db/test-query! db)
                               (send! {:conn conn}
                                      {:state "ok" :service "http" :ttl 60})
                               (send! {:conn conn}
                                      {:state "critical" :service "http" :ttl 60}))))]
      (assoc component
             :conn conn
             :chimer chimer)))
  (stop [component]
    (when-let [chime (:chimer component)]
      (chime))
    (when-let [c (:conn component)]
      (r/close! c))
    (dissoc component :conn)))

(defmacro measure-latency [riemann & body]
  `(let [t0# (System/nanoTime)
         value# (do ~@body)
         t1# (System/nanoTime)]
     (send! ~riemann {:service "http latency" :metric (- t1# t0#)})
     value#))

(defn wrap-riemann [handler riemann]
  (fn [req]
    (let [response (measure-latency riemann (handler req))]
      (send! riemann {:service (str "http status " (str/replace (:uri req) "/" "-"))
                      :state (if (= (:status response) 500) "critical" "ok")})
      response)))
