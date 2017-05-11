(ns tictag.beeminder-test
  (:require [tictag.beeminder :as sut]
            [clojure.test :as t]))

(t/deftest parse-goals
  (t/is (sut/match? :foo #{"foo"}))
  (t/is (not (sut/match? :foo #{"bar"})))
  (t/is (sut/match? [:and :foo :bar] #{"foo" "bar"}))
  (t/is (not (sut/match? [:and :foo :bar] #{"foo"})))
  (t/is (sut/match? [:or :foo :bar] #{"foo" "bar"}))
  (t/is (sut/match? [:or :foo :bar] #{"foo"}))
  (t/is (sut/match? [:and
                     [:or :foo :bar]
                     [:or :buzz :bazz]]
                    #{"foo" "buzz"}))
  (t/is (sut/match? [:or
                     [:and :foo :bar]
                     [:and :buzz :bazz]]
                    #{"foo" "bar"}))
  (t/is (sut/match? '(or "foo" "bar")
                    #{"foo" "bar"}))
  (t/is (sut/match? '(and "foo" "bar")
                    #{"foo" "bar"}))
  (t/is (sut/match? '(or (and "meow" "mix")
                         (and "foo" "bar"))
                    #{"foo" "bar"})))

