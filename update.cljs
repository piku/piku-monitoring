(ns update
  (:require
    ["child_process" :refer [execSync]]
    ["fs" :refer [readFileSync readlinkSync writeFileSync]]
    [clojure.string :refer [join]]))

(defn get-page []
  (let [re-load-avg ()
        top (.toString (execSync "top -bcn1 -w512 | head -n 20"))
        load-average (->> top
                          (re-find (js/RegExp. "load average: (.*), (.*), (.*)" "g"))
                          rest
                          (map js/parseFloat))
        [head values] (.split top (js/RegExp. "PID"))
        values (.replace values (js/RegExp. " +" "g") " ")
        lines (->> (.split values "\n")
                   rest
                   (filterv #(not (empty? %)))
                   (mapv #(vec (.split % " ")))
                   (mapv #(filterv (comp not empty?) %))
                   (mapv #(split-at 11 %)))
        processes (->> lines (mapv (fn [[stats cmd]]
                                     (let [pid (-> stats first js/parseInt)
                                           cpu (-> stats vec (get 8) js/parseFloat)
                                           mem (-> stats vec (get 9) js/parseFloat)
                                           cmd (join " " cmd)
                                           cwd (try (readlinkSync (str "/proc/" pid "/cwd")) (catch :default e ""))]
                                       {:pid pid
                                        :cpu cpu
                                        :mem mem
                                        :cmd cmd
                                        :cwd cwd})))
                       (filterv #(or (> (:cpu %) 0) (> (:mem %) 0))))]
    {:loadavg load-average
     :processes processes}))

(let [page (get-page)]
  (print (js/JSON.stringify (clj->js page))))
