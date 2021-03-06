(ns pegasus.cache
  "A simple cache using LMDB"
  (:require [clj-lmdb.simple :as lmdb]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [fort-knox.core :refer :all]
            [pegasus.utils :as utils]
            [taoensso.timbre :as timbre
             :refer (log debug info)]))

(defn create-cache-dirs
  [cache-dir]
  (utils/mkdir-if-not-exists cache-dir))

(defn initialize-caches
  [config]

  (let [two-tb    2147483648
        cache-dir (:struct-dir config)
        
        visited-db  (lmdb/make-named-db cache-dir
                                        "visited"
                                        two-tb)
        to-visit-db (lmdb/make-named-db cache-dir
                                        "to-visit"
                                        two-tb)
        robots-db   (lmdb/make-named-db cache-dir
                                        "robots"
                                        two-tb)
        hosts-db    (lmdb/make-named-db cache-dir
                                        "hosts"
                                        two-tb)
        
        visited-cache  (make-cache-from-db visited-db)
        to-visit-cache (make-cache-from-db to-visit-db)
        robots-cache   (make-cache-from-db robots-db)
        hosts-cache    (make-cache-from-db hosts-db)]
    
    ;; create cache directories
    (create-cache-dirs cache-dir)
    
    (merge
     config
     {:to-visit-cache      to-visit-cache
      :visited-cache       visited-cache
      :hosts-visited-cache hosts-cache
      :robots-cache        robots-cache})))
