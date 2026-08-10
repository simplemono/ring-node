(ns ring.middleware.multipart-params-test
  (:require ["node:fs" :as fs]
            [cljs.test :refer [deftest is testing]]
            [ring.middleware.multipart-params :refer [multipart-params-request
                                                      wrap-multipart-params]]
            [ring.middleware.multipart-params.byte-array :refer [byte-array-store]]
            [ring.middleware.multipart-params.temp-file :refer [temp-file-store]]
            [ring.util.io :as io]))

(def ^:private boundary "XXXX")

(defn- field-part [name value]
  (str "Content-Disposition: form-data; name=\"" name "\"\r\n\r\n" value))

(defn- file-part
  ([name filename content] (file-part name filename "text/plain" content))
  ([name filename content-type content]
   (str "Content-Disposition: form-data;"
        " name=\"" name "\"; filename=\"" filename "\"\r\n"
        "Content-Type: " content-type "\r\n\r\n" content)))

(defn- multipart-request [parts]
  {:request-method :post
   :headers {"content-type" (str "multipart/form-data; boundary=" boundary)
             "content-length" "0"}
   :body (io/string-stream
          (str (apply str (for [p parts] (str "--" boundary "\r\n" p "\r\n")))
               "--" boundary "--\r\n"))})

(def ^:private echo-params
  (fn [request] (select-keys request [:params :multipart-params])))

(deftest ^:async test-form-fields
  (let [handler (wrap-multipart-params echo-params)
        resp    (await (handler (multipart-request
                                 [(field-part "foo" "bar")
                                  (field-part "baz" "quux")])))]
    (is (= {"foo" "bar" "baz" "quux"} (:multipart-params resp)))
    (is (= {"foo" "bar" "baz" "quux"} (:params resp)))))

(deftest ^:async test-repeated-field-names
  (let [handler (wrap-multipart-params echo-params)
        resp    (await (handler (multipart-request
                                 [(field-part "a" "1")
                                  (field-part "a" "2")])))]
    (is (= {"a" ["1" "2"]} (:multipart-params resp)))))

(deftest ^:async test-params-are-merged
  (let [handler (wrap-multipart-params echo-params)
        request (assoc (multipart-request [(field-part "foo" "bar")])
                       :params {"pre" "set"})
        resp    (await (handler request))]
    (is (= {"pre" "set" "foo" "bar"} (:params resp)))))

(deftest ^:async test-non-multipart-request-passes-through
  (let [handler (wrap-multipart-params echo-params)
        resp    (await (handler {:request-method :get
                                 :headers {"content-type" "text/plain"}
                                 :body (io/string-stream "not multipart")}))]
    (is (= {} (:multipart-params resp)))
    (is (= {} (:params resp)))))

(deftest ^:async test-file-upload-byte-array-store
  (let [handler (wrap-multipart-params echo-params {:store (byte-array-store)})
        resp    (await (handler (multipart-request
                                 [(field-part "note" "attached")
                                  (file-part "upload" "test.txt" "file contents")])))
        upload  (get-in resp [:multipart-params "upload"])]
    (is (= "attached" (get-in resp [:multipart-params "note"])))
    (is (= "test.txt" (:filename upload)))
    (is (= "text/plain" (:content-type upload)))
    (is (= "file contents" (.toString (:bytes upload) "utf8")))))

(deftest ^:async test-file-upload-temp-file-store
  (let [handler (wrap-multipart-params echo-params {:store (temp-file-store)})
        resp    (await (handler (multipart-request
                                 [(file-part "upload" "data.bin"
                                             "application/octet-stream" "12345678")])))
        upload  (get-in resp [:multipart-params "upload"])]
    (is (= "data.bin" (:filename upload)))
    (is (= "application/octet-stream" (:content-type upload)))
    (is (= 8 (:size upload)))
    (is (string? (:tempfile upload)))
    (is (= "12345678" (fs/readFileSync (:tempfile upload) "utf8")))
    (fs/unlinkSync (:tempfile upload))))

(deftest ^:async test-multipart-params-request
  (let [request (await (multipart-params-request
                        (multipart-request [(field-part "x" "y")])))]
    (is (= {"x" "y"} (:multipart-params request)))
    (is (= {"x" "y"} (:params request)))))

(deftest ^:async test-max-file-size-returns-413
  (let [handler (wrap-multipart-params echo-params
                                       {:store (byte-array-store)
                                        :max-file-size 5})
        resp    (await (handler (multipart-request
                                 [(file-part "big" "big.txt" "0123456789")])))]
    (is (= 413 (:status resp)))))

(deftest ^:async test-max-file-count-returns-413
  (let [handler (wrap-multipart-params echo-params
                                       {:store (byte-array-store)
                                        :max-file-count 1})
        resp    (await (handler (multipart-request
                                 [(file-part "one" "1.txt" "a")
                                  (file-part "two" "2.txt" "b")])))]
    (is (= 413 (:status resp)))))

(deftest ^:async test-custom-error-handler
  (let [handler (wrap-multipart-params
                 echo-params
                 {:max-file-size 1
                  :store (byte-array-store)
                  :error-handler (fn [_] {:status 400 :headers {} :body "custom"})})
        resp    (await (handler (multipart-request
                                 [(file-part "f" "f.txt" "too large")])))]
    (is (= 400 (:status resp)))
    (is (= "custom" (:body resp)))))

(deftest ^:async test-handler-errors-are-not-swallowed
  (let [handler (wrap-multipart-params
                 (fn [_] (throw (ex-info "handler blew up" {})))
                 {:store (byte-array-store)})]
    (try
      (await (handler (multipart-request [(field-part "a" "b")])))
      (is false "expected rejection")
      (catch :default e
        (is (= "handler blew up" (ex-message e)))))))
