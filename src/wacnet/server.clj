(ns wacnet.server
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [wacnet.handler :as h]
            [bacure.core :as bac]
            [logger.timed :as logger])
  (:gen-class :main true))


(defn start-server []
  (run-jetty #'h/handler {:port 47800 :join? false}))
  


(defn -main [& m]
  (when-not (logger/maybe-start-logging) ;; start logging
    (bac/boot-up)) ;;start the bacnet device
  (start-server))
