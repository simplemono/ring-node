(ns ring.util.codec
  "Functions for encoding and decoding data.

  A ClojureScript replacement for the ring/ring-codec library, which
  ring-core depends on upstream. Uses js/encodeURIComponent, js/TextDecoder
  and js/Buffer instead of java.net.URLEncoder and friends; percent-encoding
  differs from the JVM implementation only in that spaces in url-encode are
  %20 rather than +."
  (:require [clojure.string :as str]))

(defn assoc-conj
  "Associate a key with a value in a map. If the key already exists in the
  map, a vector of values is associated with the key."
  [map key val]
  (assoc map key
         (if-let [cur (get map key)]
           (if (vector? cur)
             (conj cur val)
             [cur val])
           val)))

(defn url-encode
  "Returns the url-encoded version of the given string. The encoding argument
  exists for JVM signature parity and is ignored; encoding is always UTF-8."
  ([unencoded] (url-encode unencoded nil))
  ([unencoded _encoding]
   ;; encodeURIComponent leaves !'()* unencoded; percent-encode them for
   ;; parity with java.net.URLEncoder-based ring-codec.
   (str/replace (js/encodeURIComponent unencoded)
                #"[!'()*]"
                (fn [c] (str "%" (.toUpperCase (.toString (.charCodeAt c 0) 16)))))))

(defn url-decode
  "Returns the url-decoded version of the given string. If the encoded value
  is invalid, the original string is returned. The encoding argument exists
  for JVM signature parity and is ignored; encoding is always UTF-8."
  ([encoded] (url-decode encoded nil))
  ([encoded _encoding]
   (try (js/decodeURIComponent encoded)
        (catch :default _ encoded))))

(defn base64-encode
  "Encode a js/Buffer or js/Uint8Array into a base64 encoded string."
  [bytes]
  (.toString (js/Buffer.from bytes) "base64"))

(defn base64-decode
  "Decode a base64 encoded string into a js/Buffer."
  [encoded]
  (js/Buffer.from encoded "base64"))

(defn- percent-run-end
  "Given a string and the index of a '%', return the end index of the
  contiguous run of %XX escapes starting there, throwing on malformed
  sequences."
  [s i]
  (loop [j i]
    (if (and (< j (.-length s)) (= (.charAt s j) "%"))
      (do (when-not (re-matches #"[0-9A-Fa-f]{2}" (subs s (inc j) (+ j 3)))
            (throw (ex-info "Invalid percent-encoding" {:string s :index j})))
          (recur (+ j 3)))
      j)))

(defn- percent-run-bytes [s i end]
  (let [bytes #js []]
    (loop [j i]
      (when (< j end)
        (.push bytes (js/parseInt (subs s (inc j) (+ j 3)) 16))
        (recur (+ j 3))))
    (js/Uint8Array.from bytes)))

(defn form-decode-str
  "Decode the supplied www-form-urlencoded string using the specified
  encoding, or UTF-8 by default. If the encoded value is invalid, nil is
  returned.

  As with java.net.URLDecoder, only %XX escape sequences are interpreted in
  the supplied encoding; literal characters pass through unchanged."
  ([encoded] (form-decode-str encoded "UTF-8"))
  ([encoded encoding]
   (try
     (let [s       (str/replace encoded "+" " ")
           decoder (js/TextDecoder. (or encoding "UTF-8") #js {:fatal true})
           out     #js []]
       (loop [i 0]
         (when (< i (.-length s))
           (if (= (.charAt s i) "%")
             (let [end (percent-run-end s i)]
               (.push out (.decode decoder (percent-run-bytes s i end)))
               (recur end))
             (do (.push out (.charAt s i))
                 (recur (inc i))))))
       (.join out ""))
     (catch :default _ nil))))

(defn form-encode-str
  "Encode a string as www-form-urlencoded (spaces become +)."
  [s]
  (str/replace (url-encode s) "%20" "+"))

(defn form-encode
  "Encode the supplied value into www-form-urlencoded format. If the value is
  a map of parameters, they will be turned into a string of the format
  \"name=value&name2=value2\". The encoding argument exists for JVM signature
  parity and is ignored; encoding is always UTF-8."
  ([x] (form-encode x nil))
  ([x _encoding]
   (if (map? x)
     (->> x
          (mapcat
           (fn [[k v]]
             (let [ek (form-encode-str (str (if (keyword? k) (name k) k)))]
               (if (or (sequential? v) (set? v))
                 (map #(str ek "=" (form-encode-str (str %))) v)
                 [(str ek "=" (form-encode-str (str v)))]))))
          (str/join "&"))
     (form-encode-str (str x)))))

(defn form-decode
  "Decode the supplied www-form-urlencoded string using the specified
  encoding, or UTF-8 by default. If the encoded value is a string containing
  no '=' character, the decoded string is returned. Otherwise a map of
  parameters is returned."
  ([encoded] (form-decode encoded "UTF-8"))
  ([encoded encoding]
   (if-not (str/includes? encoded "=")
     (form-decode-str encoded encoding)
     (reduce
      (fn [m param]
        (let [[k v] (str/split param #"=" 2)
              k     (form-decode-str k encoding)
              v     (form-decode-str (or v "") encoding)]
          (if (and k v)
            (assoc-conj m k v)
            m)))
      {}
      (str/split encoded #"&")))))

(defn form-decode-map
  "Decode the supplied www-form-urlencoded string using the specified
  encoding, or UTF-8 by default. Always returns a map of parameters;
  parameters without an '=' are mapped to an empty string."
  ([encoded] (form-decode-map encoded "UTF-8"))
  ([encoded encoding]
   (let [params (form-decode encoded encoding)]
     (cond
       (map? params)     params
       (string? params)  (if (str/blank? params) {} {params ""})
       :else             {}))))
