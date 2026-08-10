(ns ring.core.protocols
  "Protocols necessary for Ring."
  (:require [clojure.string :as str]))

(defprotocol StreamableResponseBody
  "A protocol for writing data to the response body via a Node.js Writable
  stream. Implementations must return a js/Promise that resolves once the
  body has been completely written and the stream ended, and rejects if
  writing fails."
  (write-body-to-stream [body response output-stream]
    "Write a value representing a response body to a Node.js Writable stream.
    Returns a js/Promise resolved when the value has been written and the
    stream ended. The stream is deliberately not closed on error, so that the
    adapter or error middleware can potentially send extra error information
    to the client."))

;; The following private functions are replicated from ring.util.response in
;; order to allow third-party adapters to use StreamableResponseBody without
;; the need for a ring-core dependency.

;; JS regexes lack the JVM's inline (?i:...) group; the whole pattern is
;; case-insensitive instead, which is equivalent here.
(def ^:private re-charset
  #"(?i);(?:.*\s)?charset=(?:([!#$%&'*\-+.0-9A-Z\^_`a-z\|~]+)|\"((?:\\\"|[^\"])*)\")\s*(?:;|$)")

(defn- find-charset-in-content-type [content-type]
  (when-let [m (re-find re-charset content-type)]
    (or (m 1) (m 2))))

(defn- response-charset [response]
  (some->> (:headers response)
           (some (fn [[k v]] (when (= "content-type" (str/lower-case k)) v)))
           (find-charset-in-content-type)))

(defn- charset->encoding
  "Translate an HTTP charset name into a Node.js Buffer encoding. Unknown
  charsets fall back to utf8, as Node.js cannot transcode arbitrary charsets
  without additional dependencies."
  [charset]
  (case (some-> charset str/lower-case)
    ("utf-8" "utf8")                      "utf8"
    ("iso-8859-1" "latin1")               "latin1"
    ("us-ascii" "ascii")                  "ascii"
    ("utf-16le" "utf16le" "ucs-2" "ucs2") "utf16le"
    "utf8"))

(defn- response-encoding [response]
  (charset->encoding (response-charset response)))

(defn- end-stream [^js output-stream]
  (js/Promise.
   (fn [resolve reject]
     (.once output-stream "error" reject)
     (.end output-stream (fn [] (resolve nil))))))

(defn- end-stream-with-chunk [^js output-stream chunk encoding]
  (js/Promise.
   (fn [resolve reject]
     (.once output-stream "error" reject)
     (if encoding
       (.end output-stream chunk encoding (fn [] (resolve nil)))
       (.end output-stream chunk (fn [] (resolve nil)))))))

(defn- pipe-body [^js body ^js output-stream]
  (js/Promise.
   (fn [resolve reject]
     (.once body "error" reject)
     (.once output-stream "error" reject)
     (.once output-stream "finish" (fn [] (resolve nil)))
     (.pipe body output-stream))))

(defn- write-seq-body [body response ^js output-stream]
  (let [encoding (response-encoding response)]
    (js/Promise.
     (fn [resolve reject]
       (.once output-stream "error" reject)
       (doseq [chunk body]
         (.write output-stream (str chunk) encoding))
       (.end output-stream (fn [] (resolve nil)))))))

(defn- readable-stream? [x]
  (and (some? x) (fn? (.-pipe ^js x))))

(extend-protocol StreamableResponseBody
  string
  (write-body-to-stream [body response output-stream]
    (end-stream-with-chunk output-stream body (response-encoding response)))
  nil
  (write-body-to-stream [_ _ output-stream]
    (end-stream output-stream))
  default
  (write-body-to-stream [body response output-stream]
    (cond
      (js/Buffer.isBuffer body)
      (end-stream-with-chunk output-stream body nil)

      (readable-stream? body)
      (pipe-body body output-stream)

      (seqable? body)
      (write-seq-body (seq body) response output-stream)

      :else
      (js/Promise.reject
       (ex-info (str "Unrecognized response body type: " (pr-str (type body)))
                {:body body})))))
