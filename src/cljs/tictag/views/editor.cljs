(ns tictag.views.editor
  (:require [re-frame.core :as re-frame :refer [dispatch subscribe]]
            [re-com.core :as re-com]
            [clojure.string :as str]
            [cljs-time.format :as tf]
            [reagent.core :as reagent]
            [tictag.utils :refer [dispatch-n]]
            [taoensso.timbre :as timbre]
            [goog.date.DateTime]))

(defn ping-editor [id]
  (let [ping (subscribe [:ping-by-id id])]
    [re-com/h-box
     :align :center
     :gap "1em"
     :children [[re-com/label :label (tf/unparse
                                      (tf/formatter "yyyy-MM-dd HH:mm")
                                      (goog.date.DateTime. (:ping/ts @ping)))]
                [re-com/input-text
                 :model (or (:pending-ping/tags @ping)
                            (:ping/tags @ping))
                 :on-change #(dispatch-n
                              [:ping/update id :ping/tags %]
                              [:ping/save id])]]]))

(defn ping-editors [ids display]
  [re-com/v-box
   :gap "1em"
   :children (for [id (take @display @ids)]
               ^{:key id}
               [ping-editor id])])

(defn display-more [c]
  [re-com/button
   :label "View More"
   :on-click #(swap! c (fn [v]
                         (timbre/debug v (+ v 100))
                         (+ v 100)))])

(defn editor []
  (let [ids   (subscribe [:sorted-ping-ids])
        display (reagent/atom 100)]
    [re-com/v-box
     :children [[ping-editors ids display]
                [re-com/box :child [display-more display]]]]))

