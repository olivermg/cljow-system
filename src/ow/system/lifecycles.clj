(ns ow.system.lifecycles
  (:require [ow.logging.api.alpha :as log]))

(letfn [(start-or-stop-component [{:keys [ow.system/lifecycles ::prev-lifecycle-op ow.system/name ow.system/instance] :as component} op-kw]
          (if-not (= op-kw prev-lifecycle-op)
            (do (log/info (str "Component " name "#" instance ": " (clojure.core/name op-kw)))
                (reduce (fn [component lifecycle]
                          (let [f (get lifecycle op-kw identity)]
                            (-> (f component)
                                (assoc ::prev-lifecycle-op op-kw))))
                        component
                        lifecycles))
            component))]

  (defn make-start-or-stop-xf [op-kw]
    (assert (#{:start :stop} op-kw))
    (fn [rf]
      (fn
        ([] (rf))
        ([system] (rf system))
        ([system {:keys [ow.system/name ow.system/instance] :as component}]
         (let [started-component (start-or-stop-component component op-kw)]
           (rf (assoc-in system [:components name :workers instance] started-component) started-component)))))))
