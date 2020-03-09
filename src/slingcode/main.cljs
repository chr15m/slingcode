(ns slingcode.main
  (:require [alandipert.storage-atom :refer [local-storage]]
            [reagent.core :as r]))

; ***** functions ***** ;


; ***** events ***** ;


; ***** views ***** ;

(defn component-main [state]
  [:div "Hello"])

; ***** init ***** ;

(defn reload! []
  (println "reload!")
  (let [state (local-storage (r/atom {}) :slingcode-state)]
    (js/console.log "Current state:" (clj->js @state))
    (r/render [component-main state] (js/document.getElementById "app"))))

(defn main! []
  (println "main!")
  (reload!))
