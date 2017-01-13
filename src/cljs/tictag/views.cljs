(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [taoensso.timbre :as timbre]
            [clojure.string :as str]))

(defn app
  []
  (fn [] [:div "Hello"]))
