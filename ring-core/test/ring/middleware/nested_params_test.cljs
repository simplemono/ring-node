(ns ring.middleware.nested-params-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ring.middleware.nested-params :refer [parse-nested-keys
                                                   wrap-nested-params]]))

(def wrapped-params (wrap-nested-params :params))

(deftest test-parse-nested-keys
  (is (= ["foo"] (parse-nested-keys "foo")))
  (is (= ["foo" "bar"] (parse-nested-keys "foo[bar]")))
  (is (= ["foo" ""] (parse-nested-keys "foo[]")))
  (is (= ["foo" "bar" "" "baz"] (parse-nested-keys "foo[bar][][baz]"))))

(deftest test-wrap-nested-params
  (is (= {"foo" "bar"}
         (wrapped-params {:params {"foo" "bar"}})))
  (is (= {"x" {"y" "z"}}
         (wrapped-params {:params {"x[y]" "z"}})))
  (is (= {"a" {"b" {"c" "d"}}}
         (wrapped-params {:params {"a[b][c]" "d"}})))
  (is (= {"a" ["b" "c"]}
         (wrapped-params {:params {"a[]" ["b" "c"]}})))
  (is (= {"a" [{"b" "c"} {"b" "d"}]}
         (wrapped-params {:params {"a[][b]" ["c" "d"]}}))))

(deftest test-custom-key-parser
  (let [parse   #(str/split % #"\.")
        wrapped ((fn [h] (wrap-nested-params h {:key-parser parse})) :params)]
    (is (= {"a" {"b" "c"}}
           (wrapped {:params {"a.b" "c"}})))))
