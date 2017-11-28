(ns tictag.views
  (:require [reagent.core :as reagent]
            [re-frame.core :refer [subscribe dispatch]]
            [clojure.string :as str]
            [tictag.constants :refer [ENTER]]
            [tictag.events]
            [tictag.subs]
            [tictag.nav :refer [route-for]]
            [tictag.dates :refer [weeks-since-epoch days-since-epoch seconds-since-midnight]]
            [tictag.views.query]
            [tictag.views.settings]
            [tictag.views.editor]
            [tictag.views.signup]
            [tictag.views.inputs]
            [tictag.views.login]
            [tictag.views.about]
            [tictag.views.common :refer [page input]]
            [cljs-time.core :as t]
            [cljs-time.coerce :as tc]
            [cljs-time.format :as f]
            [goog.string.format]
            [taoensso.timbre :as timbre
             :refer-macros [debug]]
            [c2.scale]
            [c2.svg]
            [c2.ticks])
  (:import [goog.date.Interval]))

(defn app
  []
  (let [active-panel (subscribe [:active-panel])]
    (fn []
      [page
       (case @active-panel
         :signup    [tictag.views.signup/signup]
         :login     [tictag.views.login/login]
         :dashboard [tictag.views.query/query]
         :settings  [tictag.views.settings/settings]
         :about     [tictag.views.about/about]
         :editor    [tictag.views.editor/editor]
         ;; if :active-panel not set yet, just wait for pushy to initialize
         [:div])])))

