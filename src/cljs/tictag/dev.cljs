(ns tictag.dev
  (:require [tictag.app]
            [devtools.core :as devtools]))

(defonce runonce
  [(devtools/install! [:formatters :hints :async])
   (enable-console-print!)])

(tictag.app/main)
