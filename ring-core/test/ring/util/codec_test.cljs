(ns ring.util.codec-test
  (:require [cljs.test :refer [deftest is testing]]
            [ring.util.codec :as codec]))

(deftest test-form-decode-str
  (is (= "hello world" (codec/form-decode-str "hello+world")))
  (is (= "bat%" (codec/form-decode-str "bat%25")))
  (is (= "€" (codec/form-decode-str "%E2%82%AC")))
  (is (nil? (codec/form-decode-str "%")))
  (is (nil? (codec/form-decode-str "%zz"))))

(deftest test-form-decode
  (is (= {"a" "b"} (codec/form-decode "a=b")))
  (is (= {"a" "b", "c" "d"} (codec/form-decode "a=b&c=d")))
  (is (= {"a" ["b" "c"]} (codec/form-decode "a=b&a=c")))
  (is (= {"a" "", "b" "c"} (codec/form-decode "a&b=c")))
  (is (= "hello" (codec/form-decode "hello")))
  (is (= {"a" "b=c"} (codec/form-decode "a=b=c"))
      "only the first = separates key and value"))

(deftest test-form-decode-map
  (is (= {"a" "b"} (codec/form-decode-map "a=b")))
  (is (= {"hello" ""} (codec/form-decode-map "hello")))
  (is (= {} (codec/form-decode-map ""))))

(deftest test-form-encode
  (is (= "a=b" (codec/form-encode {"a" "b"})))
  (is (= "a=b&c=d" (codec/form-encode {"a" "b" "c" "d"})))
  (is (= "a=b&a=c" (codec/form-encode {"a" ["b" "c"]})))
  (is (= "hello+world" (codec/form-encode "hello world")))
  (is (= "a=hello+world" (codec/form-encode {"a" "hello world"}))))

(deftest test-form-encode-decode-round-trip
  (let [params {"q" "50% off & more", "tag" "a+b"}]
    (is (= params (codec/form-decode (codec/form-encode params))))))

(deftest test-url-encode-decode
  (is (= "foo%2Fbar" (codec/url-encode "foo/bar")))
  (is (= "foo/bar" (codec/url-decode "foo%2Fbar")))
  (is (= "%21" (codec/url-encode "!")))
  (testing "invalid encodings are returned unchanged"
    (is (= "%z" (codec/url-decode "%z"))))
  (testing "unicode round trip"
    (is (= "€" (codec/url-decode (codec/url-encode "€"))))))

(deftest test-base64
  (is (= "aGVsbG8=" (codec/base64-encode (js/Buffer.from "hello"))))
  (is (= "hello" (.toString (codec/base64-decode "aGVsbG8=") "utf8"))))

(deftest test-assoc-conj
  (is (= {:a 1} (codec/assoc-conj {} :a 1)))
  (is (= {:a [1 2]} (codec/assoc-conj {:a 1} :a 2)))
  (is (= {:a [1 2 3]} (codec/assoc-conj {:a [1 2]} :a 3))))
