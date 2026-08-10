(ns ring.util.io
  "Utility functions for handling Node.js streams.

  Replaces the JVM ring.util.io namespace; piped/byte-array input streams
  have no Node.js equivalent and are superseded by Readable streams."
  (:require ["node:stream" :as stream]))

(defn readable-stream?
  "True if x looks like a Node.js Readable stream."
  [x]
  (and (some? x) (fn? (.-pipe ^js x))))

(defn string-stream
  "Returns a Node.js Readable stream over the given string. Replaces
  string-input-stream from JVM Ring. The optional encoding is a Buffer
  encoding name (e.g. \"utf8\", \"latin1\")."
  ([s] (string-stream s "utf8"))
  ([s encoding]
   (.from stream/Readable #js [(js/Buffer.from s encoding)])))

(defn read-stream
  "Read a Readable stream to completion. Returns a js/Promise that resolves
  with a js/Buffer of the stream's content."
  [^js readable]
  (js/Promise.
   (fn [resolve reject]
     (let [chunks #js []]
       (.on readable "data"
            (fn [chunk]
              (.push chunks (if (string? chunk) (js/Buffer.from chunk) chunk))))
       (.once readable "error" reject)
       (.once readable "end" (fn [] (resolve (js/Buffer.concat chunks))))))))

(defn read-stream-string
  "Read a Readable stream to completion. Returns a js/Promise that resolves
  with a string decoded using the supplied charset (default UTF-8)."
  ([readable] (read-stream-string readable "utf-8"))
  ([readable charset]
   (.then (read-stream readable)
          (fn [buf] (.decode (js/TextDecoder. (or charset "utf-8")) buf)))))

(defn close!
  "Ensure a stream is destroyed, swallowing any exceptions."
  [stream]
  (when (and (some? stream) (fn? (.-destroy ^js stream)))
    (try (.destroy ^js stream) (catch :default _ nil))))

(defn last-modified-date
  "Returns the last modified date for an fs.Stats object, rounded down to the
  nearest second."
  [^js stats]
  (js/Date. (* 1000 (js/Math.floor (/ (.-mtimeMs stats) 1000)))))
