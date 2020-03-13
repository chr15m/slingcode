(ns slingcode.main
  (:require [alandipert.storage-atom :refer [local-storage]]
            [reagent.core :as r]
            ["muicss/lib/react/appbar" :as Appbar]
            ["muicss/lib/react/tabs" :as Tabs]
            ["muicss/lib/react/tab" :as Tab]
            ["muicss/lib/react/button" :as Button]
            ["muicss/lib/react/divider" :as Divider]
            ["muicss/lib/react/container" :as Container]
            ["muicss/lib/react/panel" :as Panel]
            [slingcode.icons :refer [component-icon]]))

; ***** functions ***** ;


; ***** events ***** ;


; ***** views ***** ;

(defn component-main [state]
  [:div
   [:> Appbar [:div {:class "mui--text-center mui--appbar-height mui--appbar-line-height mui--align-middle"} [:p "Slingcode apps"]]]
   [:> Container
    [:> Tabs {:class "mui--text-center"}
     [:> Tab {:value "All" :label "All"}]
     [:> Tab {:value "Examples" :label "Examples"}]
     [:> Tab {:value "Templates" :label "Templates"}]]]
   [:> Divider]

   [:> Container {:class "mui-container-fluid"}
    [:> Panel {:class "mui-row"}
     [:div {:class "mui-col-md-1"}
      #_ [:img {:width 64 :height 64 :class "mui-col-md-1"}]
      [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]]]
     [:div {:class "mui-col-md"}
      [:h3 "Example Mithril.js app"]
      [:div.containers {:class "mui--pull-right"}
       [:> Button {:variant "fab" :class "mui-btn--raised"} [component-icon :code]]
       [:> Button {:variant "fab" :class "mui-btn--raised"} [component-icon :link-out]]
       [:> Button {:variant "fab"} [component-icon :share]]]
      [:p "An simple Mithril.js app."]
      [:p.tags [:span "example"]]]]

    (for [x (range 100)]
      [:> Panel
       [:p "Hello"]])]

   [:> Container [:> Button {:color "primary" :variant "fab"} "+"]]])

; ***** init ***** ;

(defn reload! []
  (println "reload!")
  (let [state (local-storage (r/atom {}) :slingcode-state)]
    (js/console.log "Current state:" (clj->js @state))
    (r/render [component-main state] (js/document.getElementById "app"))))

(defn main! []
  (println "main!")
  (reload!))
