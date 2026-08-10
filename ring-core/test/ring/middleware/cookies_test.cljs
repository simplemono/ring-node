(ns ring.middleware.cookies-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.cookies :refer [wrap-cookies]]))

(defn- request-cookies [req-headers]
  (let [handler (wrap-cookies (fn [req] {:status 200 :headers {}
                                         :cookies-seen (:cookies req)}))]
    (.then (handler {:headers req-headers})
           (fn [resp] (:cookies-seen resp)))))

(defn- response-for [response]
  (let [handler (wrap-cookies (constantly response))]
    (handler {:headers {}})))

(deftest ^:async test-parse-cookies
  (is (= {"a" {:value "b"}}
         (await (request-cookies {"cookie" "a=b"}))))
  (is (= {"a" {:value "b"}, "c" {:value "d"}}
         (await (request-cookies {"cookie" "a=b; c=d"}))))
  (is (= {"a" {:value "b"}}
         (await (request-cookies {"cookie" "a=\"b\""})))
      "quoted values are unquoted")
  (is (= {"a" {:value "hello world"}}
         (await (request-cookies {"cookie" "a=hello+world"})))
      "values are url-decoded")
  (is (= {} (await (request-cookies {})))))

(deftest ^:async test-write-cookies
  (testing "string cookie value"
    (let [resp (await (response-for {:status 200 :headers {}
                                     :cookies {"a" "b"}}))]
      (is (= ["a=b"] (get-in resp [:headers "Set-Cookie"])))
      (is (not (contains? resp :cookies)))))

  (testing "map cookie value with attributes"
    (let [resp (await (response-for
                       {:status 200 :headers {}
                        :cookies {"a" {:value "b"
                                       :path "/"
                                       :secure true
                                       :http-only true
                                       :max-age 3600
                                       :same-site :strict}}}))
          [cookie] (get-in resp [:headers "Set-Cookie"])]
      (is (some? cookie))
      (is (re-find #"^a=b" cookie))
      (is (re-find #"; Path=/" cookie))
      (is (re-find #"; Secure" cookie))
      (is (re-find #"; HttpOnly" cookie))
      (is (re-find #"; Max-Age=3600" cookie))
      (is (re-find #"; SameSite=Strict" cookie))))

  (testing "js/Date expires attribute"
    (let [resp (await (response-for
                       {:status 200 :headers {}
                        :cookies {"a" {:value "b"
                                       :expires (js/Date. 1212492610000)}}}))
          [cookie] (get-in resp [:headers "Set-Cookie"])]
      (is (re-find #"; Expires=Tue, 03 Jun 2008 11:30:10 GMT" cookie))))

  (testing "multiple cookies"
    (let [resp (await (response-for {:status 200 :headers {}
                                     :cookies {"a" "b" "c" "d"}}))]
      (is (= #{"a=b" "c=d"}
             (set (get-in resp [:headers "Set-Cookie"]))))))

  (testing "cookie values are url-encoded"
    (let [resp (await (response-for {:status 200 :headers {}
                                     :cookies {"a" "hello world"}}))]
      (is (= ["a=hello+world"] (get-in resp [:headers "Set-Cookie"]))))))

(deftest ^:async test-cookies-request-preserved
  (let [handler (wrap-cookies (fn [req] {:status 200 :headers {}
                                         :cookies-seen (:cookies req)}))
        resp    (await (handler {:headers {"cookie" "a=b"}
                                 :cookies {"pre" {:value "set"}}}))]
    (is (= {"pre" {:value "set"}} (:cookies-seen resp))
        "existing :cookies key is not overwritten")))
