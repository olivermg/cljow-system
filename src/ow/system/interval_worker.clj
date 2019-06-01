(ns ow.system.interval-worker
  (:require [clojure.core.async :as a]
            [ow.logging :as log]))

(defn init-lifecycle-xf [rf]
  (fn
    ([] (rf))
    ([system] (rf system))
    ([system {:keys [ow.system/name ow.system/instance ow.system/interval-worker] :as component}]
     (letfn [(invoke-action [this action-name action]
               (let [info {:interval-worker-name     name
                           :interval-worker-instance instance
                           :action-name              action-name}]
                 (try
                   (log/trace invoke-action-start "running interval-worker's action" info)
                   (action this)
                   (log/debug invoke-action-success "invocation of interval-worker's action was a SUCCESS" info)
                   (catch Throwable e
                     (log/warn invoke-action-error "interval-worker's action has FAILED"
                               (assoc info :error-message (str e)))
                     e))))

             (run-worker [this {:keys [interval name action]}]
               (let [ctrlch (a/chan)]
                 (log/info run-worker-start "Starting interval-worker" {:name name :instance instance})
                 (a/go-loop [[msg ch] (a/alts! [ctrlch (a/timeout interval)])]
                   (if-not (= ch ctrlch)
                     (do (invoke-action this name action)  ;; NOTE: not doing this async on purpose, to avoid overlapping invocations
                         (recur (a/alts! [ctrlch (a/timeout interval)])))
                     (log/info run-worker-stop "Stopped interval-worker" {:name name :instance instance})))
                 ctrlch))

             (stop-worker [ctrlch]
               (a/close! ctrlch))

             (make-lifecycle []
               {:start (fn interval-worker-start [{{:keys [actions]} :ow.system/interval-worker :as this}]
                         (let [worker-chs (doall (map (partial run-worker this) actions))]
                           (assoc-in this [:ow.system/interval-worker :worker-chs] worker-chs)))
                :stop  (fn interval-worker-stop [{{:keys [worker-chs]} :ow.system/interval-worker :as this}]
                         (dorun (map stop-worker worker-chs))
                         (update-in this [:ow.system/interval-worker] dissoc :worker-chs))})]

       (let [component (if interval-worker
                         (update-in component [:ow.system/lifecycles] conj (make-lifecycle))
                         component)]
         (rf (assoc-in system [:components name :workers instance] component) component))))))


#_(let [cfg {:ca {:ow.system/interval-worker {:actions [{:interval 7000
                                                         :name     :action1
                                                         :action   (fn [this]
                                                                     (println "ACTION1"))}
                                                        {:interval 10000
                                                         :name     :action2
                                                         :action   (fn [this]
                                                                     (println "ACTION2"))}]}}}])
