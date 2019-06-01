(ns ow.system
  (:require [ow.system.dependencies :as sd]
            [ow.system.interval-worker :as siw]
            [ow.system.lifecycles :as sl]
            [ow.system.request-listener :as srl]))

;;; TODO: create spec for components definition

(letfn [(init-component-names-xf [rf]
          (fn
            ([] (rf))
            ([system] (rf system))
            ([system [name component]]
             (let [component (assoc component :ow.system/name name)]
               (rf (assoc-in system [:components name] component) component)))))

        (init-workers-xf [rf]
          (fn
            ([] (rf))
            ([system] (rf system))
            ([system {:keys [ow.system/name ow.system/instances] :as component}]
             (let [component (dissoc component :ow.system/instances)
                   workers   (reduce (fn [workers i]
                                       (conj workers (assoc component :ow.system/instance i)))
                                     [] (range (or instances 1)))
                   system    (assoc-in system [:components name] {:workers workers})]
               (reduce (fn [system worker]
                         (rf system worker))
                       system workers)))))]

  (defn init-system [components]
    (transduce (comp init-component-names-xf  ;; NOTE: order is important here, as shape of system changes in the process
                     sd/init-dependencies-xf
                     init-workers-xf
                     srl/init-request-response-channels-xf
                     siw/init-lifecycle-xf
                     srl/init-lifecycle-xf)
               (fn [& [system component]]
                 system)
               {:components components} components)))


(letfn [(lookup-component-xf [rf]
          (fn
            ([] (rf))
            ([system] (rf system))
            ([system component-name]
             (reduce (fn [system component]
                       (rf system component))
                     system (get-in system [:components component-name :workers])))))]

  (defn start-system [{:keys [start-order] :as system}]
    (transduce (comp lookup-component-xf
                     (sd/make-inject-or-deject-dependencies-xf :inject)
                     (sl/make-start-or-stop-xf :start))
               (fn [& [system component]]
                 system)
               system start-order))

  (defn stop-system [{:keys [start-order] :as system}]
    (transduce (comp lookup-component-xf
                     (sl/make-start-or-stop-xf :stop)
                     (sd/make-inject-or-deject-dependencies-xf :deject))
               (fn [& [system component]]
                 system)
               system (reverse start-order))))



#_(let [components {:c1 {:ow.system/lifecycles [{:start (fn [this]
                                                        (println "START C1")
                                                        this)
                                               :stop   (fn [this]
                                                         (println "STOP C1")
                                                         this)}]
                       :ow.system/instances 3}

                  :c2 {:ow.system/lifecycles [{:start (fn [this]
                                                        (println "START C2")
                                                        (println "  C2 DEPENDS ON C1:" (some->> this :ow.system/dependencies :c1
                                                                                                (map (fn [{:keys [ow.system/name ow.system/instance]}]
                                                                                                       (str name "#" instance)))))
                                                        this)
                                               :stop (fn [this]
                                                       (println "STOP C2")
                                                       this)}]
                       :ow.system/dependencies #{:c1}
                       :ow.system/instances 2}

                  :c3 {:ow.system/request-listener {:topics      #{:foo1}
                                                    :handler     (fn [this request]
                                                                   (println "RECEIVED REQUEST C3" request))
                                                    :retry-count 3}
                       :ow.system/lifecycles [{:start (fn [this]
                                                        (println "START C3")
                                                        (println "  C3 DEPENDS ON C4:" (some->> this :ow.system/dependencies :c4
                                                                                                (map (fn [{:keys [ow.system/name ow.system/instance]}]
                                                                                                       (str name "#" instance)))))
                                                        this)
                                               :stop (fn [this]
                                                       (println "STOP C3")
                                                       this)}]
                       :ow.system/dependencies #{:c4}}

                  :c4 {:ow.system/request-listener {:topics  #{:foo2}
                                                    :handler (fn [this request]
                                                               (println "RECEIVED REQUEST C4" request))}
                       :ow.system/lifecycles [{:start (fn [this]
                                                        (println "START C4")
                                                        this)
                                               :stop (fn [this]
                                                       (println "STOP C4")
                                                       this)}]}

                  :c5 {:ow.system/lifecycles [{:start (fn [this]
                                                        (println "START C5")
                                                        this)
                                               :stop (fn [this]
                                                       (println "STOP C5")
                                                       this)}]}

                  :w1 {:ow.system/interval-worker {:actions [{:interval 10000
                                                              :name     :action1
                                                              :action   (fn [this]
                                                                          (println "ACTION1"))}
                                                             {:interval 13000
                                                              :name     :action2
                                                              :action   (fn [this]
                                                                          (throw (Exception. "FAIL ACTION2")))}]}}}
      system (init-system components)
      _      (Thread/sleep 1000)
      system (start-system system)
      _      (Thread/sleep 30000)
      system (stop-system system)]
  (clojure.pprint/pprint system))
