#?(:clj
   (ns c2.scale
     (:use [c2.util :only [c2-obj]]
           [c2.maths :only [expt]])
     (:require [c2.maths :as maths])))
#?(:cljs
   (ns c2.scale
     (:use-macros [c2.util :only [c2-obj]])
     (:use [c2.maths :only [expt]])
     (:require [c2.maths :as maths])))

(defprotocol IInvertable
  (invert [scale] "Inverted scale"))


"Define record and corresponding constructor that accepts keyword arguments.
   The constructor function is defined to be the given name, with the record having an underscore prefix."
(defrecord Linear- [domain range]
  #?(:cljs IFn :clj clojure.lang.IFn)
  (#?(:cljs -invoke :clj invoke) [_ x]
    (let [domain-length (- (last domain) (first domain))
          range-length (- (last range) (first range))]
      (+ (first range)
         (* range-length
            (/ (- x (first domain))
               domain-length)))))

  IInvertable
  (invert [this]
    (assoc this
           :domain range
           :range domain)))

(defn linear [& {:keys [domain range]}]
  (->Linear- domain range))

(declare log)

;;Power scale
;;
;;Kwargs:
;;> *:domain* domain of scale, default [0 1]
;;
;;> *:range* range of scale, default [0 1]
(defrecord Power- [domain range]
  #?(:cljs IFn :clj clojure.lang.IFn)
  (#?(:cljs -invoke :clj invoke) [_ x]
    ((comp (linear :domain (map expt domain)
                   :range range)
           expt) x)))

;;Logarithmic scale
;;
;;Kwargs:
;;> *:domain* domain of scale, default [1 10]
;;
;;> *:range* range of scale, default [0 1]
(defrecord Log- [domain range]
  #?(:cljs IFn :clj clojure.lang.IFn)
  (#?(:cljs -invoke :clj invoke) [_ x]
    ((comp (linear :domain (map maths/log domain)
                   :range range)
           maths/log) x)))

(defn power [& {:keys [domain range]}] (->Power- domain range))
(defn log [& {:keys [domain range]}] (->Log- domain range))


