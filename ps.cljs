(ns ps
  (:require
    ["fs/promises" :as fs]
    ["fs" :refer [readlinkSync]]
    ["os" :as os]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["ps-list$default" :as ps]
    [sitefox.ui :refer [log]]
    [sitefox.db :refer [kv]]))

(def keep-for-minutes (* 60 24 31))
(def max-load 0.8)

(defn get-proc-cwd [proc]
  (p/let [pid (j/get proc :pid)
          ;path (str "/proc/" (j/get proc :pid) "/cwd")
          ;exists (fs/access path (j/get-in fs [:constants :R_OK]))
          ;cwd (when exists (fs/readlink path))
          cwd (try (readlinkSync (str "/proc/" pid "/cwd")) (catch :default _e nil))]
    (j/assoc! proc :cwd cwd)))

(defn get-processes [timestamp & [n]]
  (p/let [procs (js->clj (ps) :keywordize-keys true)
          cpu-beasts  (->> procs
                           (sort-by #(j/get % :cpu))
                           reverse
                           (take (or n 10))
                           (map get-proc-cwd)
                           p/all)
          memory-beasts  (->> procs
                              (sort-by #(j/get % :memory))
                              reverse
                              (take (or n 10))
                              (map get-proc-cwd)
                              p/all)]
    (clj->js {:timestamp timestamp
              :cpu cpu-beasts
              :memory memory-beasts})))

(defn update-history
  [history value]
  (let [history (or history #js [])]
    (j/call history :push value)
    (j/call history :slice (* -1 keep-for-minutes))))

(defn run-tick []
  "Runs every minute and stores the current load, plus processes if 5-minute load exceeds 80%."
  []
  (p/let [timestamp (-> (js/Date.) (.getTime) (/ 1000) int)
          cpus (os/cpus)
          loadavg (os/loadavg)
          over-loaded (> (/ (get loadavg 1) (j/get cpus :length)) max-load)
          store (kv "monitoring")
          load-history (.get store "load-history")
          load-history (update-history load-history #js {:timestamp timestamp :load (get loadavg 0)})
          process-history (.get store "process-history")
          processes (when over-loaded (get-processes timestamp))
          process-history (if processes
                            (update-history process-history processes)
                            process-history)
          _ (.set store "load-history" load-history)
          _ (.set store "process-history" process-history)]
    ;(print over-loaded)
    ;(print "load-history" (j/get load-history :length))
    ;(print "process-history" (j/get process-history :length))
    ;(log load-history)
    #_ (doseq [p process-history]
      (log (j/get p :timestamp)))))

(defn update-chart []
  
  )

(run-tick)
