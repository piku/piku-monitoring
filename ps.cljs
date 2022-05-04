(ns ps
  (:require
    ["fs/promises" :as fs]
    ["fs" :refer [readlinkSync writeFileSync]]
    ["os" :as os]
    ["path" :as path]
    [promesa.core :as p]
    [applied-science.js-interop :as j]
    ["ps-list$default" :as ps]
    ["vega-lite" :as vega-lite]
    ["vega" :as vega]
    [sitefox.ui :refer [log]]
    [sitefox.db :refer [kv]]
    [sitefox.util :refer [env]]))

(def keep-for-minutes (* 60 24 31))
(def max-load (env "MAX_LOAD" 80))

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
          cpu-count (j/get cpus :length)
          loadavg (os/loadavg)
          scaled-load-5 (-> loadavg (get 1) (/ cpu-count) (* 100) int)
          over-loaded (> scaled-load-5 max-load)
          store (kv "monitoring")
          load-history (.get store "load-history")
          load-history (update-history load-history #js {:timestamp timestamp
                                                         :load (-> loadavg (get 0) (/ cpu-count) (* 100) int)
                                                         :load-5 (-> loadavg (get 1) (/ cpu-count) (* 100) int)})
          process-history (.get store "process-history")
          processes (when over-loaded (get-processes timestamp))
          process-history (if processes
                            (update-history process-history processes)
                            process-history)]
    (.set store "load-history" load-history)
    (.set store "process-history" process-history)
    (print "over load?" over-loaded)
    ;(print "load-history" (j/get load-history :length))
    ;(print "process-history" (j/get process-history :length))
    ;(log load-history)
    #_ (doseq [p process-history]
         (log (j/get p :timestamp)))))

(defn format-processes [p]
  (when p
    (.join
      (.map (j/get p :cpu)
            (fn [c]
              (str
                (j/get c :cpu) "\n"
                (j/get c :cmd) "\n"
                (j/get c :cwd))))
      "\n")))

(defn update-charts []
  (p/let [dest "public"
          store (kv "monitoring")
          process-history (.get store "process-history")
          processes-by-timestamp (clj->js
                                   (into {}
                                         (map (fn [p] {(j/get p :timestamp) p}) process-history)))
          load-history (.get store "load-history")
          load-history (.map load-history (fn [l]
                                            (let [processes (j/get processes-by-timestamp (j/get l :timestamp))]
                                              (-> l
                                                  (j/assoc! :processes processes)
                                                  (j/assoc! :process (format-processes processes))
                                                  (j/update-in! [:timestamp] #(* % 1000))))))
          vl-spec {:width 800 :height 600
                   :data {:values load-history}
                   :transform [{:filter (str "datum['timestamp'] > " (-> (js/Date.) .getTime (- (* 1000 60 60 6))))}]
                   :encoding {:x {:field :timestamp
                                  :type :temporal
                                  ;:timeUnit :utcyearmonthdate
                                  :axis {:labelAngle 15
                                         :titleColor "#7e7e7e"
                                         :titleFontSize 14
                                         :labelFontSize 14}
                                  ;:bin true
                                  :timeUnit {:unit :yearmonthdatehoursminutes}
                                  ;:title nil
                                  }
                              ;:y {:field :load}
                              }
                   :layer [{:mark {;:type :line
                                   :type :bar
                                   :orient :vertical
                                   :title "Max load"
                                   ;:width {:band 1}
                                   ;:width 2
                                   ;:width 60
                                   :tooltip true}
                            :encoding {:y {:field :load
                                           :aggregate :max
                                           :format ".0%"}
                                       ;:y2 {:value 0}
                                       :color {:condition {:test "datum.processes"
                                                           :value "red"}
                                               :value "blue"}
                                       :tooltip [;{:field :load :aggregate :max}
                                                 {:field :timestamp :timeUnit {:unit :yearmonthdatehoursminutes} :title "time"}
                                                 ;{:field :process}
                                                 {:field :processes}
                                                 ;{:field :load :type :temporal :title "" :timeUnit {:unit "yearmonth" :step 1}}
                                                 ;{:field :l :format "$.3s" :title "Predicted" :aggregate :average}
                                                 ;{:field :p-max :format "$.3s" :title "Max predicted" :aggregate :max}
                                                 ]}}]}
          spec (aget (vega-lite/compile (clj->js vl-spec)) "spec")
          view (vega/View. (vega/parse spec) (clj->js {:renderer "none"}))
          hosted-vl-spec (-> vl-spec
                             (assoc :width :container :height :container))
          hosted-spec (aget (vega-lite/compile (clj->js hosted-vl-spec)) "spec")]
    ;(log load-history)
    ;(log process-history)
    ;(print (-> vl-spec clj->js (js/JSON.stringify nil 2)))
    (writeFileSync (path/join dest "spec.json") (js/JSON.stringify hosted-spec))
    (-> (.toSVG view)
        (.then (fn [svg] (writeFileSync (path/join dest "chart.svg") svg))))))

(p/do!
  (when (not (env "UPDATEONLY"))
    (run-tick))
  (update-charts))
