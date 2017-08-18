(ns tictag.resources.graph
  (:require [hiccup.core :as h]
            [taoensso.timbre :as timbre]
            [buddy.core.codecs.base64 :as b64]
            [tictag.db :as db]
            [clojure.edn :as edn]
            [clj-time.format :as f]
            [clj-time.coerce :as tc]
            [tictag.beeminder-matching :as beeminder-matching]
            [clojure.java.io :as io]
            [ring.util.io :as ring-io]
            [ring.util.response :as response]
            [c2.scale]
            [c2.svg]
            [c2.ticks]
            [tictag.jwt :as jwt]
            [tictag.utils :as utils]))

(defn round [v] (Math/round (double v)))

(defn user-id [{:keys [jwt]} req]
  (:user-id (jwt/unsign jwt (get-in req [:params :auth-token]))))

(defn b64-encode
  [s]
  (String. (b64/encode (.getBytes s "UTF-8")) "UTF-8"))

(defn b64-decode
  [s]
  (String. (b64/decode (.getBytes s "UTF-8")) "UTF-8"))

(defn style [m]
  (.trim
   (apply str
          (map #(let [[k v] %]
                  (str (name k) ":" v ";"))
               m))))

(defn svg* [[x0 y0 x0 y1 :as dims] width height & contents]
  (h/html
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :version "1.1"
          :width (format "%dpx" width)
          :height (format "%dpx" height)}
    contents]))

(defn query-fn [query]
  (if (seq query)
    (let [q (try (edn/read-string query) (catch Exception _ query))]
      (fn [{:keys [ping/tag-set]}]
        (beeminder-matching/match? q tag-set)))
    (constantly false)))

(defn pings [db user-id query]
  (let [pings (db/get-pings db user-id)]
    (filter (query-fn query) pings)))

(defn cum-axis [yscale]
  [:g {:style (style {:stroke "#3E9651"})}
   (c2.svg/axis
    yscale
    (conj (:ticks (c2.ticks/search (:domain yscale))) (second (:domain yscale)))
    :orientation :right
    :label "Hours (cum)")])

(defn format-day-to-time [day]
  (let [in-seconds (* day 24 60 60 1000)]
    (f/unparse (f/formatter "yyyy-MM-dd") (tc/from-long in-seconds))))

(defn days-axis [xscale]
  (c2.svg/axis
   xscale
   (let [[r0 r1] (:domain xscale)]
     (range r0 r1 (round (/ (- r1 r0) 10))))
   :orientation :bottom
   :text-margin 25
   :label-margin 43
   :major-tick-width 12
   :formatter format-day-to-time
   :label "Date"))

(defn time-axis [yscale]
  [:g {:style (style {:stroke "#396AB1"})}
   (c2.svg/axis yscale
                (range 0 (* 24 60 60) (* 4 60 60))
                :orientation :right
                :formatter #(str (/ % 60 60) ":00"))])

(defn hist-axis [density-yscale]
  [:g {:style (style {:stroke "#DA7C30"})}
   (c2.svg/axis density-yscale
                [0 8 16 24]
                :orientation :left
                :text-margin 25
                :label-margin 40
                :label "Hours (per day)")])

(defn circle-for-ping [ping]
  [:ellipse {:rx    2
             :ry    2
             :style (style {:opacity "0.5"})
             :fill  "#396AB1"}])

(defn matrix [xscale yscale pings]
  [:g
   (for [ping pings]
     ^{:key (:ping/id ping)}
     [:g {:transform (c2.svg/translate [(xscale (:ping/days-since-epoch ping))
                                        (yscale (:ping/seconds-since-midnight ping))])}
      (circle-for-ping ping)])])

(defn get-query [req]
  (b64-decode (get-in req [:params :query])))

(defn daily-total [freqs]
  (fn [prev today]
    (let [[_ ytotal] (or (last prev) [0 0])]
      (conj prev [today (+ (* 0.75 (or (freqs today) 0)) ytotal)]))))

(defn cumulative [xscale yscale width height margin first-day last-day day-totals]
  (let [totals (when (seq day-totals)
                 (reduce (daily-total day-totals) [] (range first-day (inc last-day))))]
    (when totals
      [:g
       [:g {:style (style {:fill         "none"
                           :stroke       "#3E9651"
                           :stroke-width "1"
                           :opacity      "0.8"})}
        (c2.svg/line
         (map
          (fn [[day total]]
            [(round (xscale day)) (round (yscale total))])
          totals))]])))

(defn histogram [xscale density-yscale height margin day-totals]
  [:g {:style (style {:fill    "#DA7C30"
                      :opacity "0.8"})}
   (for [[d freq] day-totals]
     (let [hours  (* freq 0.75)
           scaled (density-yscale hours)]
       ^{:key d}
       [:rect
        {:x      (xscale d)
         :y      scaled
         :height (- height scaled margin)
         :width  1}]))])

(defn width [req]
  (Integer. (get-in req [:params :width])))

(defn height [req]
  (Integer. (get-in req [:params :height])))


(defn svg-graph [{:as c :keys [db]} req]
  (let [width  (width req)
        height (height req)]
    (svg*
     [0 0 width height] width height
     (let [query           (get-query req)
           pings           (pings db (user-id c req) query)]
       (when (seq pings)
         (let [margin          60
               max-height      (- height margin)
               min-height      margin
               min-width       margin
               max-width       (- width margin)
               first-ping      (last pings)
               last-ping       (first pings)
               day-frequencies (->> pings (map :ping/days-since-epoch) (frequencies))
               day-scale       (c2.scale/linear :domain [(:ping/days-since-epoch first-ping)
                                                         (:ping/days-since-epoch last-ping)]
                                                :range [min-width max-width])
               time-scale      (c2.scale/linear :domain [0 (* 24 60 60)]
                                                :range [min-height max-height])
               hours-scale     (c2.scale/linear :domain [0 24]
                                                :range [max-height min-height])
               count-scale     (c2.scale/linear :domain [0 (* 0.75 (count pings))]
                                                :range [max-height min-height])]
           [:g
            [:g.axes {:style       (style {:stroke       "black"
                                           :stroke-width 1
                                           :font-weight  "100"})
                      :font-size   "14px"
                      :font-family "sans-serif"}
             [:g {:transform (c2.svg/translate [max-width 0])}
              (cum-axis count-scale)]
             [:g {:transform (c2.svg/translate [min-width 0])}
              (hist-axis hours-scale)]
             [:g {:transform (c2.svg/translate [(/ width 2) 0])}
              (time-axis time-scale)]
             [:g {:transform (c2.svg/translate [0 max-height])}
              (days-axis day-scale)]]
            [:g.plots
             [:g.matrix
              (matrix day-scale time-scale pings)]
             [:g.histogram
              (histogram day-scale hours-scale height margin day-frequencies)]
             [:g.cumulative
              (cumulative day-scale count-scale width height margin (:ping/days-since-epoch first-ping) (:ping/days-since-epoch last-ping) day-frequencies)]]]))))))


(defn authorized? [c req]
  (user-id c req))

(defn graph [c]
  (fn [req]
    {:body (svg-graph c req)
     :headers {"Content-Type" "image/svg+xml"}}))
