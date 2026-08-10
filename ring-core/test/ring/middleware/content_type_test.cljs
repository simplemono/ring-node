(ns ring.middleware.content-type-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.middleware.content-type :refer [content-type-response
                                                  wrap-content-type]]))

(deftest ^:async test-wrap-content-type
  (testing "response without content-type gets one from the uri"
    (let [handler (wrap-content-type (constantly {:status 200 :headers {}}))
          resp    (await (handler {:uri "/foo/bar.png"}))]
      (is (= {"Content-Type" "image/png"} (:headers resp)))))

  (testing "response with content-type is left alone"
    (let [handler (wrap-content-type
                   (constantly {:status 200 :headers {"Content-Type" "application/x-foo"}}))
          resp    (await (handler {:uri "/foo/bar.png"}))]
      (is (= {"Content-Type" "application/x-foo"} (:headers resp)))))

  (testing "unknown extension falls back to octet-stream"
    (let [handler (wrap-content-type (constantly {:status 200 :headers {}}))
          resp    (await (handler {:uri "/foo/bar.xxxaaa"}))]
      (is (= {"Content-Type" "application/octet-stream"} (:headers resp)))))

  (testing "custom mime types"
    (let [handler (wrap-content-type (constantly {:status 200 :headers {}})
                                     {:mime-types {"edn" "application/edn"}})
          resp    (await (handler {:uri "/all.edn"}))]
      (is (= {"Content-Type" "application/edn"} (:headers resp)))))

  (testing "nil responses pass through"
    (let [handler (wrap-content-type (constantly nil))]
      (is (nil? (await (handler {:uri "/foo/bar.png"})))))))

(deftest test-content-type-response
  (is (= {"Content-Type" "text/plain"}
         (:headers (content-type-response {:status 200 :headers {}}
                                          {:uri "/x.txt"}))))
  (is (nil? (content-type-response nil {:uri "/x.txt"}))))
