(ns ring.util.request-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.util.io :as io]
            [ring.util.request :refer [body-string character-encoding
                                       content-length content-type in-context?
                                       path-info request-url set-context
                                       urlencoded-form?]]))

(deftest test-request-url
  (is (= "http://example.com/foo?bar=baz"
         (request-url {:scheme :http
                       :uri "/foo"
                       :headers {"host" "example.com"}
                       :query-string "bar=baz"})))
  (is (= "https://example.com/"
         (request-url {:scheme :https
                       :uri "/"
                       :headers {"host" "example.com"}}))))

(deftest test-content-type
  (is (= "text/html" (content-type {:headers {"content-type" "text/html"}})))
  (is (= "text/html" (content-type {:headers {"content-type" "text/html; charset=UTF-8"}})))
  (is (nil? (content-type {:headers {}}))))

(deftest test-content-length
  (is (= 1337 (content-length {:headers {"content-length" "1337"}})))
  (is (nil? (content-length {:headers {"content-length" "nope"}})))
  (is (nil? (content-length {:headers {}}))))

(deftest test-character-encoding
  (is (= "UTF-8" (character-encoding {:headers {"content-type" "text/html; charset=UTF-8"}})))
  (is (nil? (character-encoding {:headers {"content-type" "text/html"}}))))

(deftest test-urlencoded-form?
  (is (urlencoded-form? {:headers {"content-type" "application/x-www-form-urlencoded"}}))
  (is (urlencoded-form? {:headers {"content-type" "application/x-www-form-urlencoded; charset=UTF-8"}}))
  (is (not (urlencoded-form? {:headers {"content-type" "application/json"}}))))

(deftest ^:async test-body-string
  (is (nil? (await (body-string {:body nil}))))
  (is (= "abc" (await (body-string {:body "abc"}))))
  (is (= "abc" (await (body-string {:body (list "a" "b" "c")}))))
  (is (= "hello" (await (body-string {:body (io/string-stream "hello")})))))

(deftest test-context
  (testing "path-info defaults to uri"
    (is (= "/foo" (path-info {:uri "/foo"}))))
  (testing "set-context"
    (let [request (set-context {:uri "/context/foo"} "/context")]
      (is (= "/context" (:context request)))
      (is (= "/foo" (path-info request)))
      (is (in-context? request "/context")))))
