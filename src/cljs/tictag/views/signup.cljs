(ns tictag.views.signup
  (:require [re-com.core :as re-com]
            [re-frame.core :refer [dispatch subscribe]]
            [reagent.core :as reagent]
            [goog.string :as gstr]))


(defn signup []
  (let [user              (subscribe [:pending-user])
        allowed-timezones (subscribe [:allowed-timezones])
        submit            #(do (dispatch [:user/save :temp])
                               (.preventDefault %))]
    [re-com/box
     :child
     [:form {:on-submit submit}
      [re-com/v-box
       :width "400px"
       :justify :center
       :children [[:label "Username"
                   [re-com/input-text
                    :style {:border-radius "0px"}
                    :width "100%"
                    :placeholder "Username"
                    :model (or (:pending-user/username @user) "")
                    :on-change #(dispatch [:user/update :temp :username %])]]
                  [:label "Password"
                   [re-com/input-text
                    :style {:border-radius "0px"}
                    :width "100%"
                    :placeholder "Password"
                    :model (or (:pending-user/pass @user) "")
                    :on-change #(dispatch [:user/update :temp :pass %])]]
                  [:label "Email"
                   [re-com/input-text
                    :style {:border-radius "0px"}
                    :width "100%"
                    :placeholder "Email"
                    :model (or (:pending-user/email @user) "")
                    :on-change #(dispatch [:user/update :temp :email %])]]
                  [:label "Timezone"
                   [:div
                    [re-com/single-dropdown
                     :width "100%"
                     :choices (map (fn [tz] {:id tz :label tz}) @allowed-timezones)
                     :model (:pending-user/tz @user)
                     :filter-box? true
                     :on-change #(dispatch [:user/update :temp :tz %])]]]
                  [re-com/gap :size "10px"]
                  [re-com/button
                   :on-click submit
                   :style {:width         "100%"
                           :font-size     "22px"
                           :font-weight   "300"
                           :border        "1px solid black"
                           :border-radius "0px"
                           :padding       "20px 26px"}
                   :label "Sign Up!"]]]]]))



