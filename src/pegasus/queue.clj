(ns pegasus.queue
  "Enqueue and dequeue operations"
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [durable-queue :refer :all]
            [org.bovinegenius.exploding-fish :as uri]
            [clojure.core.cache :as cache]
            [pegasus.process :as process]
            [taoensso.timbre :as timbre
             :refer (log  trace  debug  info  warn  error  fatal  report
                          logf tracef debugf infof warnf errorf fatalf reportf
                          spy get-env log-env)]))

(defn handle-robots-url
  [url config]
  (let [robots-payload (try
                         (:body
                          (client/get url
                                      {:socket-timeout 1000
                                       :conn-timeout 1000
                                       :headers
                                       {"User-Agent"
                                        (:user-agent config)}}))
                         (catch Exception e nil))

        robots-cache (:robots-cache config)
        
        host (uri/host url)]
    
    (if robots-payload
      (cache/miss robots-cache
                  host
                  robots-payload)
      (cache/miss robots-cache
                  host
                  "User-Agent *\nAllow /"))))

(defn setup-queue-worker
  [q q-name config]
  (info :setting-up-q-worker)
  (let [default-delay (:min-delay-ms config)
        init-chan (:init-chan config)]
    (async/go-loop []

      ;; a timeout first
      (async/<!
       (async/timeout
        (+ default-delay
           (rand-int 1000))))

      (info :default-delay default-delay)
      
      ;; draw a url and pass
      ;; it through the pipeline
      (info q-name)
      (try
        (let [task (take! q q-name 10 nil)]
          (when-not (nil? task)
            (let [url (deref task)]
              (info :obtained url)
              (if (= (uri/path url) "/robots.txt")
                (handle-robots-url url config)
                (async/>! init-chan {:input url
                                     :config config}))
              (complete! task))))
        (catch java.io.IOException ex
          ;; Uh-oh.  Is the durable queue messed up?
          ;; Durable queue throws an IOException if its internal
          ;; checksums don't match.
          ;; Best idea here: Communicate an error to the
          ;; crawler thread, stop, and report an error.
          ;; Reset the crawl so that the next attempt starts
          ;; over with fresh durable queue files.
          (swap! (:state config) assoc :queue-error true)))
      (when-not (:stop?
                 @(:state config))
       (recur)))))

(defn enqueue-robots
  [a-url q visited-hosts-cache]
  (let [q-name (-> a-url uri/host keyword)
        robots-url (uri/resolve-uri a-url "/robots.txt")]
    (put! q q-name robots-url)
    (cache/miss visited-hosts-cache
                (uri/host a-url)
                "1")))

(defn enqueue-url
  [url config]
  (let [q-name (-> url uri/host keyword)
        struct-dir (:struct-dir config)
        q (:queue config)
        visited-hosts (:hosts-visited-cache config)
        to-visit-cache (:to-visit-cache config)]

    ;; before we first hit a host, we need to confirm
    ;; that robots.txt has been obtained.
    ;; if not, we enqueue robots.txt and start monitoring
    ;; that queue.
    (info :enqueue url)
    (when-not (cache/lookup visited-hosts
                            (uri/host url))
      (enqueue-robots url q visited-hosts)
      (setup-queue-worker q q-name config))
    
    ;; insert this URL.
    (put! q q-name url)
    (cache/miss to-visit-cache
                url
                "1")))

(defn enqueue-pipeline
  "A pipeline component that consumes urls
  from the supplied object, enqueues them
  and continues."
  [obj config]
  (let [extracted-uris (:extracted obj)]
    (doseq [uri extracted-uris]
      (enqueue-url uri config))
    obj))

(defn build-queue-config
  [config]
  (let [struct-dir (:struct-dir config)
        q (queues struct-dir {:slab-size 1024
                              :fsync-take? true})]
    (merge config {:queue q})))

(defn global-to-visit
  "How many items are enqueued?"
  [q]
  (let [global-stats (stats q)]
    (reduce
     (fn [n [name named-q-stats]]
       (+ n (- (:enqueued named-q-stats)
               (:completed named-q-stats))))
     0
     global-stats)))
