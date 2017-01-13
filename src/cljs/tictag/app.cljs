(ns tictag.app
  (:require [goog.events :as events]
            [tictag.views]
            [reagent.core :as reagent]
            [re-frame.core :refer [dispatch dispatch-sync]])
  (:import [goog History]
           [goog.history EventType]))

(defn ^:export main
  []
  (reagent/render [tictag.views/app]
                  (.getElementById js/document "app")))
