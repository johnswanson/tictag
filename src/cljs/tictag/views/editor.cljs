(ns tictag.views.editor
  (:require [re-frame.core :as re-frame :refer [dispatch subscribe]]
            [clojure.string :as str]
            [cljs-time.format :as tf]
            [tictag.views.common :refer [input]]
            [reagent.core :as reagent]
            [tictag.utils :refer [dispatch-n]]
            [taoensso.timbre :as timbre]
            [goog.date.DateTime]))

(defn ping-editor [id]
  (let [ping (subscribe [:ping-by-id id])]
    [:tr
     [:td
      (tf/unparse (tf/formatter "yyyy-MM-dd HH:mm")
                  (goog.date.DateTime. (:ping/ts @ping)))]
     [:td
      [input
       {:type    :text
        :v       (or (:pending-ping/tags @ping)
                     (:ping/tags @ping))
        :on-save #(dispatch-n [:ping/update id :ping/tags %]
                              [:ping/save id])}]]]))


(defn ping-editors [ids display]
  [:table
   [:tr
    [:th "Time"]
    [:th "Tags"]]
   (for [id (take @display @ids)]
     ^{:key id}
     [ping-editor id])])

(defn display-more [c]
  [:button {:type :button
            :on-click #(swap! c (fn [v]
                                  (timbre/debug v (+ v 100))
                                  (+ v 100)))}
   "View More"])

(defn editor []
  (let [ids   (subscribe [:sorted-ping-ids])
        display (reagent/atom 100)]
    [:div {:style {:width "70%"
                   :margin :auto
                   :padding "3em 0"}}
     [ping-editors ids display]
     [display-more display]]))

