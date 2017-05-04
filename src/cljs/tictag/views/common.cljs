(ns tictag.views.common
  (:require [re-frame.core :refer [dispatch subscribe]]
            [re-com.core :refer [h-box box v-box]]
            [tictag.nav :refer [route-for]]
            [goog.string :as str]))

(defn link [route-name current-page]
   [box
    :class (if (= current-page route-name)
             "ttvc-link inactive"
             "ttvc-link active")
    :child [:a {:href (route-for route-name)}
            (str/capitalize (name route-name))]])

(defn nav-for-user [user current-page]
  [h-box
   :class "ttvc-navbar"
   :height "3em"
   :justify :around
   :align :center
   :children [[box :child [:span "TTC"]]
              (when-not user [link :login current-page])
              (when-not user [link :signup current-page])
              (when user [link :dashboard current-page])
              (when user [link :settings current-page])
              (when user [link :logout current-page])]])

(defn- nav [& {:keys [children]}]
  (let [user         (subscribe [:authorized-user])
        current-page (subscribe [:active-panel])]
    [nav-for-user @user @current-page]))

(defn page
  [& content]
  [v-box
   :children [[nav]
              content]])
