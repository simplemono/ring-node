(ns ring.middleware.file
  "Middleware to serve files from a directory."
  (:require ["node:fs" :as fs]
            [ring.middleware.head :as head]
            [ring.util.async :as async]
            [ring.util.codec :as codec]
            [ring.util.request :as request]
            [ring.util.response :as response]))

(defn- ensure-dir
  "Ensures that a directory exists at the given path, throwing if one does not."
  [dir-path]
  (when-not (try (.isDirectory (fs/statSync dir-path))
                 (catch :default _ false))
    (throw (ex-info (str "Directory does not exist: " dir-path)
                    {:path dir-path}))))

(defn file-request
  "If request matches a static file, returns a js/Promise of a response
  containing it. Otherwise returns a js/Promise of nil. See: wrap-file."
  ([request root-path]
   (file-request request root-path {}))
  ([request root-path options]
   (let [options (merge {:root (str root-path)
                         :index-files? true
                         :allow-symlinks? false}
                        options)]
     (if (#{:get :head} (:request-method request))
       (let [path (subs (codec/url-decode (request/path-info request)) 1)]
         (async/then (response/file-response path options)
                     #(head/head-response % request)))
       (js/Promise.resolve nil)))))

(defn- wrap-file-prefer-files [handler root-path options]
  (fn [request]
    (async/then (file-request request root-path options)
                (fn [response]
                  (or response (handler request))))))

(defn- wrap-file-prefer-handler [handler root-path options]
  (fn [request]
    (async/then (async/call handler request)
                (fn [response]
                  (if (= 404 (:status response))
                    (file-request request root-path options)
                    response)))))

(defn wrap-file
  "Wrap an handler such that the directory at the given root-path is checked
  for a static file with which to respond to the request, proxying the request
  to the wrapped handler if such a file does not exist.

  Accepts the following options:

  :index-files?    - look for index.* files in directories, defaults to true
  :allow-symlinks? - serve files through symbolic links, defaults to false
  :prefer-handler? - prioritize handler response over files, defaults to false"
  ([handler root-path]
   (wrap-file handler root-path {}))
  ([handler root-path options]
   (ensure-dir root-path)
   (if (:prefer-handler? options)
     (wrap-file-prefer-handler handler root-path options)
     (wrap-file-prefer-files   handler root-path options))))
