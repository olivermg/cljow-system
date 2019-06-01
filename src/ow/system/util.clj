(ns ow.system.util)

(defn worker-name [{:keys [ow.system/name ow.system/instance] :as this}]
  (str name "#" instance))
