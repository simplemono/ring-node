(ns ring.util.response
  "Functions for generating and augmenting response maps.

  Unlike JVM Ring, file-response is asynchronous (it returns a js/Promise),
  and resource/url responses do not exist: there is no runtime classpath on
  Node.js."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [ring.util.io :refer [last-modified-date]]
            [ring.util.parsing :as parsing]
            [ring.util.time :refer [format-date]]))

(def ^:private fsp (.-promises fs))

(def redirect-status-codes
  "Map a keyword to a redirect status code."
  {:moved-permanently 301
   :found 302
   :see-other 303
   :temporary-redirect 307
   :permanent-redirect 308})

(defn redirect
  "Returns a Ring response for an HTTP 302 redirect. Status may be
  a key in redirect-status-codes or a numeric code. Defaults to 302"
  ([url] (redirect url :found))
  ([url status]
   {:status  (redirect-status-codes status status)
    :headers {"Location" url}
    :body    ""}))

(defn created
  "Returns a Ring response for a HTTP 201 created response."
  ([url] (created url nil))
  ([url body]
   {:status  201
    :headers {"Location" url}
    :body    body}))

(defn bad-request
  "Returns a 400 'bad request' response."
  [body]
  {:status  400
   :headers {}
   :body    body})

(defn not-found
  "Returns a 404 'not found' response."
  [body]
  {:status  404
   :headers {}
   :body    body})

(defn response
  "Returns a skeletal Ring response with the given body, status of 200, and no
  headers."
  [body]
  {:status  200
   :headers {}
   :body    body})

(defn status
  "Returns an updated Ring response with the given status."
  ([status]
   {:status  status
    :headers {}
    :body    nil})
  ([resp status]
   (assoc resp :status status)))

(defn header
  "Returns an updated Ring response with the specified header added."
  [resp name value]
  (assoc-in resp [:headers name] (str value)))

(defn content-type
  "Returns an updated Ring response with the a Content-Type header
  corresponding to the given content-type."
  [resp content-type]
  (header resp "Content-Type" content-type))

(defn find-header
  "Looks up a header in a Ring response (or request) case insensitively,
  returning the header map entry, or nil if not present."
  [resp header-name]
  (let [header-name (str/lower-case header-name)]
    (->> (:headers resp)
         (filter #(= header-name (str/lower-case (key %))))
         (first))))

(defn get-header
  "Looks up a header in a Ring response (or request) case insensitively,
  returning the value of the header, or nil if not present."
  [resp header-name]
  (some-> resp (find-header header-name) val))

(defn update-header
  "Looks up a header in a Ring response (or request) case insensitively,
  then updates the header with the supplied function and arguments in the
  manner of update-in."
  [resp header-name f & args]
  (let [header-key (or (some-> resp (find-header header-name) key) header-name)]
    (update-in resp [:headers header-key] #(apply f % args))))

(defn charset
  "Returns an updated Ring response with the supplied charset added to the
  Content-Type header."
  [resp charset]
  (update-header resp "Content-Type"
                 (fn [content-type]
                   (-> (or content-type "text/plain")
                       (str/replace #";\s*charset=[^;]*" "")
                       (str "; charset=" charset)))))

(defn get-charset
  "Gets the character encoding of a Ring response."
  [resp]
  (some-> (get-header resp "Content-Type")
          parsing/find-content-type-charset))

(defn set-cookie
  "Sets a cookie on the response. Requires the handler to be wrapped in the
  wrap-cookies middleware."
  [resp name value & [opts]]
  (assoc-in resp [:cookies name] (merge {:value value} opts)))

(defn response?
  "True if the supplied value is a valid response map."
  [resp]
  (and (map? resp)
       (integer? (:status resp))
       (map? (:headers resp))))

;; -- file responses ---------------------------------------------------------

(defn- directory-transversal?
  "Check if a path contains '..'."
  [p]
  (contains? (set (str/split p #"/|\\")) ".."))

(defn- ^:async stat-or-nil [p]
  (try (await (.stat fsp p))
       (catch :default _ nil)))

(defn- ^:async realpath-or-nil [p]
  (try (await (.realpath fsp p))
       (catch :default _ nil)))

(defn- contained? [child parent]
  (or (= child parent)
      (str/starts-with? child (str parent path/sep))))

(defn- ^:async safely-find-file [file-path {:keys [root allow-symlinks?]}]
  (if root
    (let [full     (path/join root file-path)
          resolved (path/resolve full)
          rootres  (path/resolve root)]
      (when (and (contained? resolved rootres)
                 (or allow-symlinks?
                     (let [rp (await (realpath-or-nil full))
                           rr (await (realpath-or-nil root))]
                       (and rp rr (contained? rp rr)))))
        full))
    file-path))

(defn- ^:async find-index-file
  "Search the directory for an index file."
  [dir]
  (let [names (vec (await (.readdir fsp dir)))]
    (or (first (filter #(= % "index.html") names))
        (first (filter #(= % "index.htm") names))
        (first (filter #(str/starts-with? (str/lower-case %) "index.") names)))))

(defn- ^:async find-file [file-path opts]
  (when-let [p (await (safely-find-file file-path opts))]
    (when-let [stats (await (stat-or-nil p))]
      (cond
        (.isDirectory stats)
        (when (:index-files? opts true)
          (when-let [index-name (await (find-index-file p))]
            (let [ip     (path/join p index-name)
                  istats (await (stat-or-nil ip))]
              (when (and istats (.isFile istats))
                [ip istats]))))

        (.isFile stats)
        [p stats]))))

(defn- content-length [resp len]
  (if len
    (header resp "Content-Length" len)
    resp))

(defn- last-modified [resp last-mod]
  (if last-mod
    (header resp "Last-Modified" (format-date last-mod))
    resp))

(defn- ^:async file-response* [filepath options]
  (when-let [[p stats] (await (find-file filepath options))]
    (-> (response (.createReadStream fs p))
        (content-length (.-size stats))
        (last-modified (last-modified-date stats)))))

(defn file-response
  "Returns a js/Promise of a Ring response map to serve a static file, or of
  nil if an appropriate file does not exist. The response :body is a Node.js
  Readable stream (fs.createReadStream), whose .path property holds the
  file's path.
  Options:
    :root            - take the filepath relative to this root path
    :index-files?    - look for index.* files in directories (defaults to true)
    :allow-symlinks? - allow symlinks that lead to paths outside the root path
                       (defaults to false)"
  ([filepath]
   (file-response* filepath {}))
  ([filepath options]
   (file-response* filepath options)))
