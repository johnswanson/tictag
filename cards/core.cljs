(ns cards.core
  (:require [re-frame.core :as re-frame]
            [tictag.views :as v]
            [devtools.core :as devtools]
            [devcards.core :as dc]
            [tictag.views.settings :as vs]
            [tictag.views.query :as vq]
            [c2.ticks]
            [c2.scale]
            [cljs.test :refer [is testing async]]
            [reagent.core :as reagent]
            [taoensso.timbre :as timbre])
  (:require-macros [devcards.core :refer [defcard-rg deftest]]))

(defonce runonce
  [(devtools/install! [:formatters :hints :async])
   (enable-console-print!)])

(defcard-rg foobar
  [:svg {:height 500 :width 500}
   [vq/pie-chart {:x 250
                  :y 250
                  :r 200}
    [{:name "Test" :start 0 :end 0.4}
     {:name "Test" :start 0.4 :end 0.5}
     {:name "Test" :start 0.5 :end 0.75}
     {:name "Test" :start 0.75 :end 1.0}]]
   [:circle {:cx 250 :cy 250 :r 190 :fill "white"}]])


(dc/deftest a-test
  "## percent-radian-coordinate conversions"
  (testing "pct-to-angle"
    (is (= (vq/scale [1 1] {:r 250}) [500 500]))
    (is (= (vq/invert-y [500 500] {:r 250}) [500 0]))
    (is (= (vq/invert-y [250 250] {:r 250}) [250 250]))
    (is (= (vq/invert-y [300 300] {:r 250}) [300 200]))))
