(ns ow.system.request-listener
  (:require [clojure.core.async :as a]
            [ow.logging.api.alpha :as log]
            [ow.clojure :as owclj]
            [ow.clojure.async :as owa]
            [ow.system.util :as osu]))

;;; TODO: implement spec'd requests/events

(defn init-request-response-channels-xf [rf]
  (let [out-ch  (a/chan)
        in-mult (a/mult out-ch)]
    (fn
      ([] (rf))
      ([system] (rf system))
      ([system {:keys [ow.system/name ow.system/instance ow.system/request-listener] :as component}]
       (let [system    (if request-listener
                         (let [{:keys [topic-fn topics]} request-listener
                               topic-fn                  (or topic-fn :topic)]
                           (update-in system [:components name :worker-sub]
                                      #(or % (let [in-tap (a/tap in-mult (a/chan))
                                                   in-pub (a/pub in-tap topic-fn)]
                                               (owa/chunking-sub in-pub topics (a/chan)
                                                                 (fn [_] (some-> (log/get-checkpoints-root) :id)))))))
                         system)
             component (update-in component [:ow.system/requester :out-ch] #(or % out-ch))
             system    (assoc-in system [:components name :workers instance] component)]
         (rf system component))))))

(defn init-lifecycle-xf [rf]
  (letfn [(handle-exception [this e & [try]]
            (log/warn "handler exception" {:error e
                                           :try   (or try :last)}))

          (apply-handler [{{:keys [handler retry-count retry-delay-fn]} :ow.system/request-listener :as this}
                          request-map]
            (log/with-checkpoint apply-handler
              (let [retry-count    (or retry-count 1)
                    retry-delay-fn (or retry-delay-fn
                                       (owclj/make-retry-delay-log10-f :exp 2.0
                                                                       :cap (* 30 60 1000)
                                                                       :varpct 10.0))
                    requests       (->> request-map
                                        (map (fn [[topic request]]
                                               [topic (get request :request)]))
                                        (into {}))]
                (owclj/tries [retry-count  ;; TODO: implement abort (e.g. when system gets shut down in between retries)
                              :try-sym       try
                              :exception-f   (partial handle-exception this)
                              :retry-delay-f retry-delay-fn]
                             (log/trace "invoke handler" {:try try})
                             (handler this requests)
                             (catch Throwable e
                               (handle-exception this e)
                               e)))))

          (handle-response [this request-map response]
            (let [response-chs (->> request-map
                                    vals
                                    (map :response-ch)
                                    (remove nil?))]
              (cond
                ;;; 1. if caller(s) is/are waiting for a response, return response to it/them, regardless of if it's an exception or not:
                (not-empty response-chs)       (do (log/trace "sending back handler's response" response)
                                                   (doseq [response-ch response-chs]
                                                     (a/put! response-ch [response])
                                                     (a/close! response-ch)))
                ;;; 2. if nobody is waiting for our response, throw exceptions:
                (instance? Throwable response) (do (log/trace "throwing handler's exception" response)
                                                   (throw response))
                ;;; 3. if nobody is waiting for our response, simply evaluate to regular responses:
                true                           (do (log/trace "discarding handler's response" response)
                                                   response))))

          (run-loop [this in-ch]
            (a/go-loop [topics-map (a/<! in-ch)]
              (when-not (nil? topics-map)
                (log/with-logging-info (some-> topics-map first val log/detach)  ;; TODO: how can we remove this internal knowledge of ow.logging?
                  (future
                    (->> (apply-handler this topics-map)
                         (handle-response this topics-map))))
                (recur (a/<! in-ch)))))

          (make-lifecycle [system component-name]
            (let [worker-sub (get-in system [:components component-name :worker-sub])]
              {:start (fn request-listener-start [{{:keys [topic-fn topic]} :ow.system/request-listener :as this}]
                        (let [in-ch (a/pipe worker-sub (a/chan))]
                          (run-loop this in-ch)
                          (assoc-in this [:ow.system/request-listener :in-ch] in-ch)))
               :stop  (fn request-listener-stop [this]
                        (update-in this [:ow.system/request-listener :in-ch] #(and % a/close! nil)))}))]

    (fn
      ([] (rf))
      ([system] (rf system))
      ([system {:keys [ow.system/name ow.system/instance ow.system/request-listener] :as component}]
       (let [component (if request-listener
                         (update-in component [:ow.system/lifecycles] conj (make-lifecycle system name))
                         component)]
         (rf (assoc-in system [:components name :workers instance] component) component))))))

(defn emit [{:keys [ow.system/requester] :as this} topic request]
  ;;; TODO: do we need an option to specify retry-count per event?
  (let [{:keys [out-ch]} requester
        event-map {:topic   topic
                   :request request}]
    (log/trace "emitting event")
    (a/put! out-ch (-> event-map log/attach))))

(defn request [{:keys [ow.system/requester] :as this} topic request & {:keys [timeout]}]
  (let [{:keys [out-ch]} requester
        response-ch (a/promise-chan)
        request-map {:topic       topic
                     :request     request
                     :response-ch response-ch}
        receipt     (a/go
                      (let [timeout-ch                             (a/timeout (or timeout 30000))
                            [[response :as response-container] ch] (a/alts! [response-ch timeout-ch])]
                        (if (= ch response-ch)
                          (if-not (nil? response-container)
                            response
                            (ex-info "response channel was closed"
                                     (log/log-data :debug "response channel closed" request)))
                          (ex-info "timeout while waiting for response"
                                   (log/log-data :debug "response timeout" request)))))]
    (log/trace request-request "doing request")
    (a/put! out-ch (-> request-map log/attach))
    (let [response (a/<!! receipt)]
      (log/trace request-response "received response" response)
      (if-not (instance? Throwable response)
        response
        (throw response)))))



#_(let [cfg {:ca {:ow.system/request-listener {:topics         #{:a}
                                             :input-spec     :tbd
                                             :output-spec    :tbd
                                             :handler        (log/fn cahandler [this {:keys [a] :as request-map}]
                                                               (println "AAAAAAAAAAAAAAAAAAAAAAAAAAAa111" (:id (log/get-trace-root)))
                                                               (log/warn ca-log nil request-map)
                                                               (Thread/sleep 500)
                                                               (emit this :b {:bdata 1})
                                                               (Thread/sleep 500)
                                                               (emit this :c {:cdata 1}))}}

           :cb {:ow.system/request-listener {:topics         #{:b}
                                             :handler        (log/fn cbhandler [this {:keys [b] :as request-map}]
                                                               (println "AAAAAAAAAAAAAAAAAAAAAAAAAAAa222" (:id (log/get-trace-root)))
                                                               (log/warn cb-log nil request-map)
                                                               (Thread/sleep 500)
                                                               #_(emit this :d1 {:d1data 1})
                                                               (-> (request this :d1 {:d1data 1} :timeout 5000)
                                                                   (doto (println "RRRRRRRRRRRRRRRRRRRRRR"))))}}

           :cc {:ow.system/request-listener {:topics         #{:c}
                                             :output-signals [:d2]
                                             :handler        (log/fn cchandler [this {:keys [c] :as request-map}]
                                                               (println "AAAAAAAAAAAAAAAAAAAAAAAAAAAa333" (:id (log/get-trace-root)))
                                                               (log/warn cc-log nil request-map)
                                                               (Thread/sleep 1000)
                                                               (emit this :d2 {:d2data 1}))}}

           :cd {:ow.system/request-listener {:topics         #{:d1 :d2}
                                             :handler        (log/fn cdhandler [this {:keys [d1 d2] :as request-map}]
                                                               (println "AAAAAAAAAAAAAAAAAAAAAAAAAAAa444" (:id (log/get-trace-root)))
                                                               (log/warn cd-log nil request-map)
                                                               :d1d2response)}}}
      system (-> (ow.system/init-system cfg)
                 (ow.system/start-system))
      ca     (get-in system [:components :ca :workers 0])]
  (emit ca :a {:adata 1})
  (Thread/sleep 5000)
  (ow.system/stop-system system))
