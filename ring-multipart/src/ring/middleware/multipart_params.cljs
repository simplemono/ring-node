(ns ring.middleware.multipart-params
  "Middleware that parses multipart request bodies into parameters.

  This middleware is necessary to handle file uploads from web browsers. It
  is backed by the busboy npm package.

  Ring-node comes with two different multipart storage engines included:

    ring.middleware.multipart-params.byte-array/byte-array-store
    ring.middleware.multipart-params.temp-file/temp-file-store

  A storage engine is a function that takes a map with :filename,
  :content-type and :stream (a Node.js Readable) keys, and returns the value
  for the parameter in the multipart parameter map, or a js/Promise of it.
  The store must consume the stream.

  Differences from JVM Ring: the :progress-fn option is not ported (listen on
  the request stream for progress), and the HTML5 \"_charset_\" field is not
  interpreted (a busboy limitation); per-part charsets and :encoding /
  :fallback-encoding are honoured."
  (:require ["busboy" :as busboy]
            [clojure.string :as str]
            [ring.middleware.multipart-params.temp-file :as tf]
            [ring.util.codec :refer [assoc-conj]]
            [ring.util.request :as req]))

(defn- multipart-form? [request]
  (= (req/content-type request) "multipart/form-data"))

(def ^:private default-store (delay (tf/temp-file-store)))

(defn- limit-error [limit]
  (ex-info (str "Multipart limit exceeded: " (name limit)) {::error limit}))

(defn- parse-error [cause]
  (ex-info (str "Multipart parse error: " (ex-message cause)) {::error :parse} cause))

(defn- multipart-error? [e]
  (contains? (ex-data e) ::error))

(defn- busboy-config [request fallback-encoding
                      {:keys [max-file-size max-file-count limits]}]
  (clj->js
   {:headers        (:headers request)
    :defCharset     (str/lower-case fallback-encoding)
    :limits         (cond-> (or limits {})
                      max-file-size  (assoc :fileSize max-file-size)
                      max-file-count (assoc :files max-file-count))}))

(defn- run-busboy
  "Feed the request body through busboy, returning a js/Promise of the flat
  parameter map."
  [request store fallback-encoding options]
  (js/Promise.
   (fn [resolve reject]
     (let [bb      (busboy (busboy-config request fallback-encoding options))
           params  (atom {})
           pending (atom [])
           failed  (atom false)
           fail!   (fn [e]
                     (when-not @failed
                       (reset! failed true)
                       (reject e)))]
       (.on bb "field"
            (fn [name value _info]
              (swap! params assoc-conj name value)))
       (.on bb "file"
            (fn [name ^js file-stream ^js info]
              (.once file-stream "limit"
                     (fn [] (fail! (limit-error :max-file-size))))
              (let [item {:filename     (.-filename info)
                          :content-type (.-mimeType info)
                          :stream       file-stream}]
                (swap! pending conj
                       (-> (js/Promise.resolve (store item))
                           (.then (fn [value]
                                    (swap! params assoc-conj name value)))
                           (.catch fail!))))))
       (.on bb "filesLimit"  (fn [] (fail! (limit-error :max-file-count))))
       (.on bb "partsLimit"  (fn [] (fail! (limit-error :parts))))
       (.on bb "fieldsLimit" (fn [] (fail! (limit-error :fields))))
       (.on bb "error"       (fn [e] (fail! (parse-error e))))
       (.on bb "close"
            (fn []
              (-> (js/Promise.all (to-array @pending))
                  (.then (fn [_]
                           (when-not @failed
                             (resolve @params)))))))
       (if-let [body (:body request)]
         (.pipe ^js body bb)
         (fail! (parse-error (ex-info "Missing request body" {}))))))))

(defn- ^:async parse-multipart-params* [request options]
  (when (multipart-form? request)
    (let [store    (or (:store options) @default-store)
          encoding (or (:encoding options)
                       (:fallback-encoding options)
                       (req/character-encoding request)
                       "UTF-8")]
      (await (run-busboy request store encoding options)))))

(defn parse-multipart-params
  "Parse a multipart request map and return a js/Promise of a map of
  parameters, or of nil if the request is not a multipart form. For a list of
  available options, see: wrap-multipart-params."
  ([request]
   (parse-multipart-params* request {}))
  ([request options]
   (parse-multipart-params* request options)))

(defn- ^:async multipart-params-request* [request options]
  (let [params (or (await (parse-multipart-params* request options)) {})]
    (merge-with merge request
                {:multipart-params params}
                {:params params})))

(defn multipart-params-request
  "Adds :multipart-params and :params keys to request, returning a js/Promise
  of the updated request. See: wrap-multipart-params."
  ([request]
   (multipart-params-request* request {}))
  ([request options]
   (multipart-params-request* request options)))

(defn content-too-large-handler
  "A handler function that responds with a minimal 413 Content Too Large
  response."
  [_]
  {:status  413
   :headers {"Content-Type" "text/plain; charset=UTF-8"}
   :body    "Uploaded content exceeded limits."})

(defn wrap-multipart-params
  "Middleware to parse multipart parameters from a request. Adds the
  following keys to the request map:

  :multipart-params - a map of multipart parameters
  :params           - a merged map of all types of parameter

  The following options are accepted

  :encoding          - character encoding used as the default for parts that
                       do not specify one in their content type. Overrides the
                       request character encoding.

  :fallback-encoding - as :encoding, but with lower precedence. Has no effect
                       if :encoding is also set.

  :store             - a function that stores a file upload. The function
                       should expect a map with :filename, :content-type and
                       :stream keys, and its return value (or resolved
                       js/Promise value) will be used as the value for the
                       parameter in the multipart parameter map. The default
                       storage function is the temp-file-store.

  :max-file-size     - the maximum allowed size of a file in bytes. If nil or
                       omitted, there is no limit.

  :max-file-count    - the maximum number of files allowed in a single
                       request. If nil or omitted, there is no limit.

  :limits            - a map of raw busboy limits (e.g. :fields, :fieldSize,
                       :parts); :max-file-size and :max-file-count take
                       precedence over the equivalent busboy keys.

  :error-handler     - a handler that is invoked when a limit is exceeded or
                       the multipart body is malformed. Defaults to the
                       content-too-large-handler function. Errors raised by
                       the :store function or the wrapped handler are not
                       routed here; they propagate as rejections."
  ([handler]
   (wrap-multipart-params handler {}))
  ([handler options]
   (let [error-handler (:error-handler options content-too-large-handler)]
     (fn [request]
       (.then (multipart-params-request* request options)
              handler
              (fn [e]
                (if (multipart-error? e)
                  (error-handler request)
                  (throw e))))))))
