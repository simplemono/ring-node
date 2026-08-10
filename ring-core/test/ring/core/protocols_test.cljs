(ns ring.core.protocols-test
  (:require ["node:stream" :as stream]
            [cljs.test :refer [deftest is testing]]
            [ring.core.protocols :refer [write-body-to-stream]]
            [ring.util.io :as io]))

(defn- ^:async written [body response]
  (let [out    (stream/PassThrough.)
        done   (write-body-to-stream body response out)
        result (await (io/read-stream out))]
    (await done)
    result))

(deftest ^:async test-string-body
  (is (= "Hello World" (.toString (await (written "Hello World" {:headers {}})) "utf8"))))

(deftest ^:async test-string-body-with-charset
  (let [response {:headers {"Content-Type" "text/plain; charset=ISO-8859-1"}}
        buf      (await (written "æble" response))]
    (is (.equals buf (js/Buffer.from #js [0xe6 0x62 0x6c 0x65])))))

(deftest ^:async test-buffer-body
  (is (= "raw bytes"
         (.toString (await (written (js/Buffer.from "raw bytes") {:headers {}})) "utf8"))))

(deftest ^:async test-seq-body
  (is (= "onetwothree"
         (.toString (await (written (list "one" "two" "three") {:headers {}})) "utf8"))))

(deftest ^:async test-nil-body
  (is (= "" (.toString (await (written nil {:headers {}})) "utf8"))))

(deftest ^:async test-stream-body
  (is (= "streamed"
         (.toString (await (written (io/string-stream "streamed") {:headers {}})) "utf8"))))

(deftest ^:async test-invalid-body
  (try
    (await (write-body-to-stream 42 {:headers {}} (stream/PassThrough.)))
    (is false "expected rejection")
    (catch :default e
      (is (re-find #"Unrecognized response body" (ex-message e))))))
