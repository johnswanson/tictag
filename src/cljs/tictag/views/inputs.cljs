(ns tictag.views.inputs
  (:require [re-com.core :as re-com]))

(defn input [temp errs label]
  [re-com/input-text
   :style {:border-radius "0px"}
   :width "100%"
   :placeholder label
   :model temp
   :on-change #(reset! temp %)
   :status (when @errs :error)])

(defn input-password [temp errs]
  [re-com/input-password
   :style {:border-radius "0px"}
   :width "100%"
   :placeholder "Password"
   :model temp
   :on-change #(reset! temp %)
   :status (when @errs :error)])

(defn input-timezone [temp allowed-timezones]
  [:div
   [re-com/single-dropdown
    :width "100%"
    :choices (map (fn [tz] {:id tz :label tz}) @allowed-timezones)
    :model temp
    :filter-box? true
    :on-change #(reset! temp %)]])
