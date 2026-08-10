(ns ring.middleware.file-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [cljs.test :refer [deftest is testing]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.util.io :as io]))

(defn- make-tmp-dir []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "ring-node-file-test-"))]
    (fs/writeFileSync (path/join dir "hello.txt") "hello file")
    dir))

(def ^:private fallback
  (constantly {:status 200 :headers {} :body "fallback"}))

(deftest ^:async test-wrap-file
  (let [dir     (make-tmp-dir)
        handler (wrap-file fallback dir)]
    (testing "serves existing files"
      (let [resp (await (handler {:request-method :get :uri "/hello.txt" :headers {}}))]
        (is (= 200 (:status resp)))
        (is (= "hello file" (await (io/read-stream-string (:body resp)))))))
    (testing "falls through to handler for missing files"
      (let [resp (await (handler {:request-method :get :uri "/nope.txt" :headers {}}))]
        (is (= "fallback" (:body resp)))))
    (testing "non-GET requests fall through"
      (let [resp (await (handler {:request-method :post :uri "/hello.txt" :headers {}}))]
        (is (= "fallback" (:body resp)))))
    (testing "HEAD requests are served with nil body"
      (let [resp (await (handler {:request-method :head :uri "/hello.txt" :headers {}}))]
        (is (= 200 (:status resp)))
        (is (nil? (:body resp)))))))

(deftest ^:async test-wrap-file-prefer-handler
  (let [dir     (make-tmp-dir)
        handler (wrap-file (constantly {:status 404 :headers {} :body "none"})
                           dir {:prefer-handler? true})]
    (testing "404 from handler falls back to files"
      (let [resp (await (handler {:request-method :get :uri "/hello.txt" :headers {}}))]
        (is (= 200 (:status resp)))))
    (testing "non-404 from handler wins"
      (let [handler (wrap-file fallback dir {:prefer-handler? true})
            resp    (await (handler {:request-method :get :uri "/hello.txt" :headers {}}))]
        (is (= "fallback" (:body resp)))))))

(deftest test-wrap-file-requires-directory
  (is (thrown-with-msg? js/Error #"Directory does not exist"
                        (wrap-file fallback "/no/such/dir/anywhere"))))
