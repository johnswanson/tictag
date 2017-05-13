(ns tictag.views.common
  (:require [re-frame.core :refer [dispatch subscribe]]
            [re-com.core :as re-com :refer [h-box box v-box hyperlink]]
            [tictag.nav :refer [route-for]]
            [goog.string :as str]))

(defn link [route-name current-page]
   [box
    :class (if (= current-page route-name)
             "ttvc-link inactive"
             "ttvc-link active")
    :child [re-com/hyperlink-href
            :href (route-for route-name)
            :label (str/capitalize (name route-name))]])

(defn nav-for-user [user current-page]
  [h-box
   :class "ttvc-navbar"
   :height "3em"
   :justify :around
   :align :center
   :children [[box :child [:span "TTC"]]
              [link :about current-page]
              (when-not user [link :login current-page])
              (when-not user [link :signup current-page])
              (when user [link :dashboard current-page])
              (when user [link :settings current-page])
              (when user [link :logout current-page])]])

(defn- nav []
  (let [user         (subscribe [:authorized-user])
        current-page (subscribe [:active-panel])]
    (fn []
      [nav-for-user @user @current-page])))

(defn footer [] [h-box
                 :justify :center
                 :children [[box
                             :child ""]]])

(defn page
  [& content]
  [v-box
   :class "page"
   :gap "50px"
   :height "100%"
   :justify :between
   :children [[nav]
              [h-box
               :justify :center
               :children [content]]
              [footer]]])
