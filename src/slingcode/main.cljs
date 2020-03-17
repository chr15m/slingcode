(ns slingcode.main
  (:require 
    [alandipert.storage-atom :refer [local-storage]]
    [reagent.core :as r]
    [shadow.resource :as rc]
    [slingcode.icons :refer [component-icon]]))

(def boilerplate (rc/inline "slingcode/boilerplate.html"))

; ***** data ***** ;

(defn make-app []
  {:created (js/Date.)
   :src boilerplate
   :tags ["My apps"]})

; ***** functions ***** ;


; ***** events ***** ;

(defn add-app! [apps ev]
  (swap! apps assoc (str (random-uuid)) (make-app)))

(defn open-app [app ev]
  (let [src (app :src)
        w (js/window.open)]
    (-> w .-document (.write src))))

(defn edit-app [app ev]
  
  )

; ***** views ***** ;

(defn component-main [state]
  (let [apps (r/cursor state [:apps])]
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

      (for [[id app] @apps]
        [:div.app {:on-click (partial open-app app)}
         [:div.columns
          [:div.column
           [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]]]
          [:div.column
           [:p.title (or (app :title) "Untitled app") [:span {:class "link-out"} [component-icon :link-out]]]
           [:p (app :description)]
           [:p.tags (for [t (app :tags)] [:span t])]]]
         [:div.actions
          [:button {:on-click (partial edit-app app)} [component-icon :code]]
          [:button [component-icon :share]]]])]

     [:button#add-app {:on-click (partial add-app! apps)} "+"]]))

; ***** init ***** ;

(defn reload! []
  (println "reload!")
  (let [state (local-storage (r/atom {}) :slingcode-state)]
    (js/console.log "Current state:" (clj->js @state))
    ;(reset! state nil)
    (r/render [component-main state] (js/document.getElementById "app"))))

(defn main! []
  (println "main!")
  (reload!))
