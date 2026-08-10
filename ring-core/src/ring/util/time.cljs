(ns ring.util.time
  "Functions for dealing with time and dates in HTTP requests."
  (:require [clojure.string :as str]))

(def ^:private months
  {"Jan" 0 "Feb" 1 "Mar" 2 "Apr" 3 "May" 4  "Jun" 5
   "Jul" 6 "Aug" 7 "Sep" 8 "Oct" 9 "Nov" 10 "Dec" 11})

;; The three date formats of RFC 2616 section 3.3.1, parsed explicitly since
;; js/Date.parse is implementation-defined outside ISO 8601.
(def ^:private re-rfc1123
  #"^\w{3}, (\d{1,2}) (\w{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) (?:GMT|UTC?)$")

(def ^:private re-rfc1036
  #"^\w+, (\d{1,2})-(\w{3})-(\d{2}) (\d{2}):(\d{2}):(\d{2}) (?:GMT|UTC?)$")

(def ^:private re-asctime
  #"^\w{3} (\w{3}) ( \d|\d{2}) (\d{2}):(\d{2}):(\d{2}) (\d{4})$")

(defn- utc-date [year month day h m s]
  (when-some [month (months month)]
    (js/Date. (js/Date.UTC year month day h m s))))

(defn- int* [s] (js/parseInt s 10))

(defn- attempt-parse [date]
  (or (when-some [[_ d mo y h mi s] (re-matches re-rfc1123 date)]
        (utc-date (int* y) mo (int* d) (int* h) (int* mi) (int* s)))
      (when-some [[_ d mo y h mi s] (re-matches re-rfc1036 date)]
        (let [yy (int* y)]
          (utc-date (if (< yy 70) (+ 2000 yy) (+ 1900 yy))
                    mo (int* d) (int* h) (int* mi) (int* s))))
      (when-some [[_ mo d h mi s y] (re-matches re-asctime date)]
        (utc-date (int* y) mo (int* d) (int* h) (int* mi) (int* s)))))

(defn- trim-quotes [s]
  (str/replace s #"^'|'$" ""))

(defn parse-date
  "Attempt to parse a HTTP date. Returns a js/Date, or nil if unsuccessful."
  [http-date]
  (attempt-parse (trim-quotes http-date)))

(defn format-date
  "Format a js/Date as RFC1123 format."
  [date]
  ;; ES2018+ specifies toUTCString as exactly the RFC1123 format.
  (.toUTCString ^js date))
