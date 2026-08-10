(ns ring.middleware.keyword-params-test
  (:require [cljs.test :refer [deftest is]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]))

(def wrapped-params (wrap-keyword-params :params))

(deftest test-wrap-keyword-params
  (is (= {:foo "bar" :biz "bat"}
         (wrapped-params {:params {"foo" "bar" "biz" "bat"}})))
  (is (= {:foo-bar "baz"}
         (wrapped-params {:params {"foo-bar" "baz"}})))
  (is (= {:foo? "bar"}
         (wrapped-params {:params {"foo?" "bar"}})))
  (is (= {:foo {:bar "baz"}}
         (wrapped-params {:params {"foo" {"bar" "baz"}}})))
  (is (= {:foo [{:bar "1"} {:bar "2"}]}
         (wrapped-params {:params {"foo" [{"bar" "1"} {"bar" "2"}]}}))))

(deftest test-invalid-keywords-left-as-strings
  (is (= {"foo bar" "baz"}
         (wrapped-params {:params {"foo bar" "baz"}})))
  (is (= {"123" "baz"}
         (wrapped-params {:params {"123" "baz"}})))
  (is (= {"ns/foo" "bar"}
         (wrapped-params {:params {"ns/foo" "bar"}}))
      "namespaced keys need :parse-namespaces?"))

(deftest test-namespaced-keywords
  (let [wrapped ((fn [h] (wrap-keyword-params h {:parse-namespaces? true})) :params)]
    (is (= {:ns/foo "bar"}
           (wrapped {:params {"ns/foo" "bar"}})))))

(deftest test-unicode-keys
  (is (= {:äöü "bar"}
         (wrapped-params {:params {"äöü" "bar"}}))))
