(ns ring.util.response-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [deftest is testing]]
            [ring.util.io :as io]
            [ring.util.response :refer [bad-request charset content-type
                                        created file-response find-header
                                        get-charset get-header header not-found
                                        redirect response response? set-cookie
                                        status update-header]]))

(deftest test-response
  (is (= {:status 200 :headers {} :body "foo"} (response "foo"))))

(deftest test-status
  (is (= {:status 404 :headers {} :body nil} (status 404)))
  (is (= {:status 404 :headers {} :body "foo"} (status (response "foo") 404))))

(deftest test-redirect
  (is (= {:status 302 :headers {"Location" "/x"} :body ""} (redirect "/x")))
  (is (= 303 (:status (redirect "/x" :see-other))))
  (is (= 308 (:status (redirect "/x" 308)))))

(deftest test-simple-responses
  (is (= 201 (:status (created "/x"))))
  (is (= 400 (:status (bad-request "no"))))
  (is (= 404 (:status (not-found "gone")))))

(deftest test-headers
  (let [resp (-> (response "") (header "X-Foo" "bar"))]
    (is (= "bar" (get-header resp "X-Foo")))
    (is (= "bar" (get-header resp "x-foo")))
    (is (= ["X-Foo" "bar"] (find-header resp "x-FOO")))
    (is (nil? (get-header resp "X-Bar")))))

(deftest test-update-header
  (let [resp (-> (response "") (header "X-Foo" "bar")
                 (update-header "x-foo" str "baz"))]
    (is (= "barbaz" (get-header resp "X-Foo")))))

(deftest test-content-type-and-charset
  (let [resp (-> (response "") (content-type "text/html"))]
    (is (= "text/html" (get-header resp "Content-Type")))
    (is (= "text/html; charset=UTF-8"
           (-> resp (charset "UTF-8") (get-header "Content-Type"))))
    (is (= "UTF-8" (-> resp (charset "UTF-8") get-charset)))
    (testing "charset replaces existing charset"
      (is (= "text/html; charset=UTF-16"
             (-> resp (charset "UTF-8") (charset "UTF-16")
                 (get-header "Content-Type")))))))

(deftest test-set-cookie
  (is (= {:status 200 :headers {} :body "" :cookies {"a" {:value "b" :path "/"}}}
         (-> (response "") (set-cookie "a" "b" {:path "/"})))))

(deftest test-response?
  (is (response? {:status 200 :headers {}}))
  (is (not (response? {:status "200" :headers {}})))
  (is (not (response? {:headers {}}))))

;; -- file responses ---------------------------------------------------------

(defn- make-tmp-dir []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "ring-node-test-"))]
    (fs/writeFileSync (path/join dir "hello.txt") "hello file")
    (fs/mkdirSync (path/join dir "subdir"))
    (fs/writeFileSync (path/join dir "subdir" "index.html") "<h1>index</h1>")
    dir))

(deftest ^:async test-file-response
  (let [dir (make-tmp-dir)]
    (testing "existing file"
      (let [resp (await (file-response "hello.txt" {:root dir}))]
        (is (= 200 (:status resp)))
        (is (= "10" (get-header resp "Content-Length")))
        (is (some? (get-header resp "Last-Modified")))
        (is (= "hello file" (await (io/read-stream-string (:body resp)))))))
    (testing "missing file"
      (is (nil? (await (file-response "nope.txt" {:root dir})))))
    (testing "directory transversal is refused"
      (is (nil? (await (file-response "../../../../etc/passwd" {:root dir})))))
    (testing "directory serves index file"
      (let [resp (await (file-response "subdir" {:root dir}))]
        (is (= "<h1>index</h1>" (await (io/read-stream-string (:body resp)))))))
    (testing "index files can be disabled"
      (is (nil? (await (file-response "subdir" {:root dir :index-files? false})))))))
