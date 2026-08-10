(ns ring.middleware.params-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.params :refer [params-request wrap-params]]
            [ring.util.io :as io]))

(def wrapped-echo
  (wrap-params (fn [req] (select-keys req [:params :query-params :form-params]))))

(deftest ^:async wrap-params-query-params-only
  (let [req  {:request-method :get
              :headers {}
              :query-string "foo=bar&biz=bat%25"}
        resp (await (wrapped-echo req))]
    (is (= {"foo" "bar" "biz" "bat%"} (:params resp)))
    (is (= {"foo" "bar" "biz" "bat%"} (:query-params resp)))
    (is (= {} (:form-params resp)))))

(deftest ^:async wrap-params-query-and-form-params
  (let [req  {:request-method :post
              :headers {"content-type" "application/x-www-form-urlencoded"}
              :query-string "foo=bar"
              :body (io/string-stream "biz=bat%25")}
        resp (await (wrapped-echo req))]
    (is (= {"foo" "bar" "biz" "bat%"} (:params resp)))
    (is (= {"foo" "bar"} (:query-params resp)))
    (is (= {"biz" "bat%"} (:form-params resp)))))

(deftest ^:async wrap-params-not-form-encoded
  (let [req  {:request-method :post
              :headers {"content-type" "application/json"}
              :body (io/string-stream "{\"foo\": \"bar\"}")}
        resp (await (wrapped-echo req))]
    (is (= {} (:form-params resp)))
    (is (= {} (:params resp)))))

(deftest ^:async wrap-params-always-assocs-maps
  (let [req  {:request-method :get
              :headers {}
              :query-string ""}
        resp (await (wrapped-echo req))]
    (is (= {} (:query-params resp)))
    (is (= {} (:form-params resp)))
    (is (= {} (:params resp)))))

(deftest ^:async wrap-params-encoding
  (let [req  {:request-method :post
              :headers {"content-type"
                        "application/x-www-form-urlencoded; charset=UTF-16LE"}
              :body (io/string-stream "hello=world" "utf16le")}
        resp (await (wrapped-echo req))]
    (is (= {"hello" "world"} (:form-params resp)))))

(deftest ^:async params-request-existing-params-preserved
  (testing "existing :form-params short-circuits body reading"
    (let [req (await (params-request {:request-method :post
                                      :headers {}
                                      :form-params {"a" "1"}
                                      :query-params {"b" "2"}}))]
      (is (= {"a" "1"} (:form-params req)))
      (is (= {"b" "2"} (:query-params req))))))

(deftest ^:async wrap-params-merges-into-existing-params
  (let [handler (wrap-params (fn [req] (:params req)))
        params  (await (handler {:request-method :get
                                 :headers {}
                                 :params {"pre" "set"}
                                 :query-string "foo=bar"}))]
    (is (= {"pre" "set" "foo" "bar"} params))))
