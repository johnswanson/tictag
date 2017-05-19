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

(defn wrap-riemann [handler riemann]
  (fn [req]
    (let [t0       (System/nanoTime)
          response (try (handler req)
                        (catch Exception e {:error e}))
          t1       (System/nanoTime)
          error?   (or (:error response)
                       (and (number? (:status response))
                            (>= (:status response) 500)
                            (< (:status response) 600)))]
      (send! riemann {:service "http latency"
                      :state (if error? "warning" "ok")
                      :metric (- t1 t0)})
      response)))
