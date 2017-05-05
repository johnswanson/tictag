(ns tictag.beeminder
  (:require [instaparse.core :as insta]))

(defmulti match? (fn [a _] (type a)))

(defmethod match? nil [_ _] false)

(defmethod match? #?(:clj clojure.lang.Keyword :cljs cljs.core/Keyword)
  [a b]
  (b a))

(defmethod match? #?(:clj clojure.lang.PersistentVector :cljs cljs.core/PersistentVector)
  [[pred & args] b]
  (case pred
    :and (every? #(match? % b) args)
    :or  (some #(match? % b) args)))

(def p (insta/parser "
 EXPR = AND | OR | CNST | PARENEXPR;
 OPENPAREN = '(';
 CLOSEPAREN = ')';
 PARENEXPR = OPENPAREN EXPR CLOSEPAREN;
 AND = EXPR ' and ' EXPR;
 OR = EXPR ' or ' EXPR;
 CNST = #'\\w+';"))

(defmulti parse first)
(defmethod parse :CNST [[_ arg]] (keyword arg))
(defmethod parse :EXPR [[_ args]] (parse args))
(defmethod parse :AND [[_ a _ b]] [:and (parse a) (parse b)])
(defmethod parse :OR [[_ a _ b]] [:or (parse a) (parse b)])
(defmethod parse :PARENEXPR [[_ _ expr _]] (parse expr))

(defn str->parsed [rules] (try (parse (p rules))
                               (catch #?(:clj Exception :cljs js/Error) e
                                   nil)))
