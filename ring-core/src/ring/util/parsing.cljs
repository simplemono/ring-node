(ns ring.util.parsing
  "Regular expressions for parsing HTTP.

  For internal use.")

(def ^{:doc "HTTP token: 1*<any CHAR except CTLs or tspecials>. See RFC2068"}
  re-token
  #"[!#$%&'*\-+.0-9A-Z\^_`a-z\|~]+")

(def ^{:doc "HTTP quoted-string: <\"> *<any TEXT except \"> <\">. See RFC2068."}
  re-quoted
  #"\"((?:\\\"|[^\"])*)\"")

(def ^{:doc "HTTP value: token | quoted-string. See RFC2109"}
  re-value
  (str "(" (.-source re-token) ")|" (.-source re-quoted)))

;; JS regexes lack the JVM's inline (?i:...) group; the whole pattern is
;; case-insensitive instead, which is equivalent here.
(def ^{:doc "Pattern for pulling the charset out of the content-type header"}
  re-charset
  (re-pattern (str "(?i);(?:.*\\s)?charset=(?:" re-value ")\\s*(?:;|$)")))

(defn find-content-type-charset
  "Return the charset of a given a content-type string."
  [s]
  (when-let [m (re-find re-charset s)]
    (or (m 1) (m 2))))
