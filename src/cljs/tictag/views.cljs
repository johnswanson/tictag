(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.events]
            [tictag.subs]
            [cljs-time.core :as t]
            [cljs-time.format :as f]))

(defn value-pixel-mapper [domain range]
  (let [max-domain  (apply max domain)
        min-domain  (apply min domain)
        domain-diff (- max-domain min-domain)

        max-range  (apply max range)
        min-range  (apply min range)
        range-diff (- max-range min-range)]
    {:pixel-fn (fn [x]
                 (+ min-range (* range-diff (/ (- x min-domain) domain-diff))))
     :value-fn (fn [p]
                 (+ min-domain (* domain-diff (/ p range-diff))))}))

(defn days-since [d1 d2]
  (Math/round
   (/ (- d2 d1)
      (* 24 60 60 1000))))

(defn days-since-epoch [date]
  (days-since (t/epoch) date))

(defn datapoint [{:keys [xpixel ypixel old-local-time local-time] :as d} active?]
  [:circle {:on-mouse-over #(js/console.log old-local-time local-time)
            :cx    xpixel
            :cy    ypixel
            :r     3
            :style {:opacity (if active? "0.8" "0.2")}
            :fill  (if active? "purple" "black")}])


(defn seconds-since-midnight [date]
  (when date
    (/ (- date (t/at-midnight date))
       (* 1000))))

(defn graph [width height pings]
  [:div
   (when (seq pings)
     (let [data              (map #(let [local-time (:local-time %)]
                                     (assoc %
                                            :x (days-since-epoch local-time)
                                            :y (seconds-since-midnight local-time)))
                                  pings)
           xs                (map :x data)
           ys                (map :y data)
           x-range           [0 width]
           y-range           [0 height]
           x-domain          [(apply min xs) (apply max xs)]
           y-domain          [0 (* 24 60 60)]
           {x-fn  :pixel-fn
            x-inv :value-fn} (value-pixel-mapper x-domain x-range)

           {y-fn  :pixel-fn
            y-inv :value-fn} (value-pixel-mapper y-domain y-range)
           data              (map #(assoc %
                                          :xpixel (x-fn (:x %))
                                          :ypixel (y-fn (:y %)))
                                  data)]
       [:svg {:style {:width (str width "px") :height (str height "px")}}
        [:g {:style {:stroke "black" :stroke-width 1}}
         [:line {:x1 0 :x2 0 :y1 0 :y2 height}]
         [:line {:x1 0 :x2 width :y1 height :y2 height}]]
        [:g
         (for [d data]
           ^{:key (str (:local-time d))}
           [datapoint d (:active? d)])]]))])

(defn app
  []
  (let [pings (subscribe [:pings])]
    (fn []
      [:div "Hello you"
       [:span {:on-click #(dispatch [:fetch-pings])} "Click Me"]
       [:input {:type :text
                :on-change #(dispatch [:update-ping-query (.. % -target -value)])}]
       [graph 500 500 @pings]])))

