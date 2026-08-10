(ns ring.middleware.multipart-params.byte-array
  "A multipart storage engine for storing uploads as in-memory Buffers."
  (:require [ring.util.io :as io]))

(defn byte-array-store
  "Returns a function that stores multipart file parameters in memory. The
  multipart parameters will be stored as maps with the following keys:

  :filename     - the name of the uploaded file
  :content-type - the content type of the uploaded file
  :bytes        - a js/Buffer containing the uploaded content"
  []
  (fn [item]
    (.then (io/read-stream (:stream item))
           (fn [buf]
             (-> (select-keys item [:filename :content-type])
                 (assoc :bytes buf))))))
