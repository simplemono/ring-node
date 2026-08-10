(ns ring.middleware.multipart-params.temp-file
  "A multipart storage engine for storing uploads in temporary files."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn- make-temp-path []
  (path/join (os/tmpdir) (str "ring-multipart-" (random-uuid))))

(defn- expired? [p expiry-time]
  (< (.-mtimeMs (fs/statSync p))
     (- (js/Date.now) (* expiry-time 1000))))

(defn- remove-old-files [file-set expiry-time]
  (doseq [p @file-set]
    (try
      (when (expired? p expiry-time)
        (fs/unlinkSync p)
        (swap! file-set disj p))
      (catch :default _
        ;; stat failed: the file is already gone
        (swap! file-set disj p)))))

(defn- start-clean-up [file-set expires-in]
  (when expires-in
    (.unref (js/setInterval #(remove-old-files file-set expires-in)
                            (* expires-in 1000)))))

(defn- ensure-exit-clean-up [file-set]
  (js/process.on "exit"
                 (fn []
                   (doseq [p @file-set]
                     (try (fs/unlinkSync p) (catch :default _ nil))))))

(defn- pipe-to-file [^js stream file-path]
  (js/Promise.
   (fn [resolve reject]
     (let [out (fs/createWriteStream file-path)]
       (.once out "error" reject)
       (.once stream "error" reject)
       (.once out "finish" (fn [] (resolve (.-bytesWritten out))))
       (.pipe stream out)))))

(defn temp-file-store
  "Returns a function that stores multipart file parameters as temporary
  files. Accepts the following options:

  :expires-in - delete temporary files older than this many seconds
                (defaults to 3600 - 1 hour); nil disables periodic cleanup

  The multipart parameters will be stored as js/Promises of maps with the
  following keys:

  :filename     - the name of the uploaded file
  :content-type - the content type of the uploaded file
  :tempfile     - the path of the temporary file containing the uploaded data
  :size         - the size in bytes of the uploaded data"
  ([] (temp-file-store {:expires-in 3600}))
  ([{:keys [expires-in]}]
   (let [file-set (atom #{})
         clean-up (delay (start-clean-up file-set expires-in))]
     (ensure-exit-clean-up file-set)
     (fn [item]
       (force clean-up)
       (let [temp-path (make-temp-path)]
         (swap! file-set conj temp-path)
         (.then (pipe-to-file (:stream item) temp-path)
                (fn [size]
                  (-> (select-keys item [:filename :content-type])
                      (assoc :tempfile temp-path
                             :size size)))))))))
