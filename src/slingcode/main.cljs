(ns slingcode.main
  (:require [alandipert.storage-atom :refer [local-storage]]
            [reagent.core :as r]
            [slingcode.icons :refer [component-icon]]))

; ***** functions ***** ;


; ***** events ***** ;


; ***** views ***** ;

(defn component-main [state]
  [:div
   [:section#header
    [:div#logo
     [:img {:src "logo.svg"}]
     [:span "Slingcode"]
     ;[:nav "ipsum"]
     [:svg {:width "100%" :height "60px"}
      [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,52 100,0 50,-50 5000,0"}] 
      [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,57 103,0 50,-50 5000,0"}]]]]

   [:section#apps
    [:section#tags
     [:ul
      [:li.active [:a {:href "#all"} "All"]]
      [:li [:a {:href "#examples"} "Examples"]]
      [:li [:a {:href "#templates"} "Templates"]]]]

    (for [x (range 10)]
      [:div.app
       [:div.columns
        [:div.column
         [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]]]
        [:div.column
         [:p.title "Example Mithril.js app"]
         [:p "A simple Mithril app demonstrating ipsum lorem blah blah."]
         [:p.tags [:span "example"] [:span "demo"] [:span "mithril"]]]]
       [:div.actions
        [:button [component-icon :code]]
        [:button [component-icon :share]]
        [:button [component-icon :link-out]]]])]

   [:button#add-app "+"]])

; ***** init ***** ;

(defn reload! []
  (println "reload!")
  (let [state (local-storage (r/atom {}) :slingcode-state)]
    (js/console.log "Current state:" (clj->js @state))
    (r/render [component-main state] (js/document.getElementById "app"))))

(defn main! []
  (println "main!")
  (reload!))
