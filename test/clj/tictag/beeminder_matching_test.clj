(ns tictag.beeminder-matching-test
  (:require [tictag.beeminder-matching :as sut]
            [clojure.test :as t]
            [taoensso.timbre :as timbre]))

(def tests
  {:yes [[:foo #{"foo"}]
         [[:and :foo :bar] #{"foo" "bar"}]
         [[:or :foo :bar] #{"foo"}]
         [[:or :foo :bar] #{"foo" "bar"}]
         [[:and
           [:or :foo :bar]
           [:or :buzz :bazz]] #{"foo" "buzz"}]
         [[:or
           [:and :foo :bar]
           [:and :buzz :bazz]] #{"foo" "bar"}]
         ['(or "foo" "bar") #{"foo" "bar"}]
         ['(and "foo" "bar") #{"foo" "bar"}]
         ['(or (and "foo" "bar" "burn" "boo")
               (and "buzz" "bazz")) #{"foo" "bar" "burn" "boo"}]
         ['(not "foo") #{"bar"}]
         ['(not "foo" "bar") #{"baz"}]]
   :no [[:foo #{"bar"}]
        ['(or "foo" "bar") #{"buzz"}]
        ['(and "foo" "bar") #{"foo"}]
        ['(and (or "foo" "bar") (or "buzz" "bazz")) #{"foo" "bar"}]
        ['(not "foo" "bar") #{"foo" "bang"}]]})

(t/deftest matches
  (doseq [[tags ping] (:yes tests)]
    (t/is (sut/valid? tags))
    (t/is (sut/match? tags ping))))

