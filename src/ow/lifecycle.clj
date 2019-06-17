(ns ow.lifecycle  ;; TODO: should we move this into a separate library?
  (:require [ow.logging.api.alpha :as log]))

;;; NOTE: these are only for backwards compatibility:
(defn construct [& args])
(defn get-name [& args])
(defn get-type [& args])



(defmulti start* (fn [this dependencies] (type this)))
(defmulti stop* (fn [this] (type this)))

(defmethod start* :default [this dependencies] (merge this
                                                      dependencies
                                                      {::dependencies (keys dependencies)}))
(defmethod stop* :default [this] (let [dependencies (->> this
                                                         ::dependencies
                                                         (select-keys this))]
                                   {:dependencies dependencies
                                    :this this}))

(defn start
  ([this dependencies]
   (log/info (str "Starting " (type this)))
   (start* this dependencies))
  ([this]
   (start this {})))

(defn stop [this]
  (log/info (str "Stopping " (type this)))
  (stop* this))
