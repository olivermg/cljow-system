(ns ow.system.dependencies
  (:require [clojure.set :as set]
            [ow.logging :as log]
            [ubergraph.core :as ug]
            [ubergraph.alg :as uga]))

(defn init-dependencies-xf [rf]
  (let [dags (volatile! (ug/digraph))]
    (fn
      ([] (rf))
      ([system]
       (when-not (-> @dags uga/connect uga/dag?)
         (throw (ex-info "circular dependencies detected" {:dependency-graph (with-out-str (ug/pprint @dags))})))
       (let [start-order        (-> @dags uga/connect uga/post-traverse)
             missing-components (set/difference (set (ug/nodes @dags))
                                                (set start-order))
             start-order        (apply conj start-order missing-components)]
         (rf (assoc system :start-order start-order))))
      ([system {:keys [ow.system/name ow.system/dependencies] :as component}]
       (vswap! dags #(let [graph (ug/add-nodes % name)]
                       (->> dependencies
                            (map (fn [d] [name d]))
                            (apply ug/add-edges graph))))
       (rf system component)))))


(letfn [(inject-dependencies [system {:keys [::prev-dependency-op ow.system/name ow.system/instance] :as component}]
          (if-not (= prev-dependency-op :inject)
            (-> (update component :ow.system/dependencies
                        #(->> (map (fn [depcn]
                                     (log/debug inject-dependencies (str "Injecting into " name "#" instance ": " depcn))
                                     [depcn (get-in system [:components depcn :workers])])
                                   %)
                              (into {})))
                (assoc ::prev-dependency-op :inject))
            component))

        (deject-dependencies [_ {:keys [::prev-dependency-op ow.system/name ow.system/instance] :as component}]
          (if-not (= prev-dependency-op :deject)
            (-> (update component :ow.system/dependencies
                        #(-> (map (fn [[depcn _]]
                                    (log/debug deject-dependencies (str "Dejecting from " name "#" instance ": " depcn))
                                    depcn)
                                  %)
                             set))
                (assoc ::prev-dependency-op :deject))
            component))]

  (defn make-inject-or-deject-dependencies-xf [op-kw]
    (assert (#{:inject :deject} op-kw))
    (let [op (case op-kw
               :inject inject-dependencies
               :deject deject-dependencies)]
      (fn [rf]
        (fn
          ([] (rf))
          ([system] (rf system))
          ([system {:keys [ow.system/name ow.system/instance] :as component}]
           (let [resulting-component (op system component)]
             (rf (assoc-in system [:components name :workers instance] resulting-component) resulting-component))))))))

(defn get-dependencies [{:keys [ow.system/dependencies] :as this}]
  (reduce (fn [dependencies [name components]]
            (assoc dependencies name (rand-nth components)))
          {} dependencies))

(defn get-dependency [{:keys [ow.system/dependencies] :as this} name]
  (get (get-dependencies this) name))
