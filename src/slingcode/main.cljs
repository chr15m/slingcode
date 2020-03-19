(ns slingcode.main
  (:require 
    [alandipert.storage-atom :refer [local-storage]]
    [reagent.core :as r]
    [shadow.resource :as rc]
    [slingcode.icons :refer [component-icon]]
    ["codemirror" :as CodeMirror]
    ["codemirror/mode/htmlmixed/htmlmixed" :as htmlmixed]
    ["codemirror/mode/xml/xml" :as xml]
    ["codemirror/mode/css/css" :as css]
    ["codemirror/mode/javascript/javascript" :as javascript]))

(js/console.log htmlmixed xml css javascript)

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

(defn edit-app [state id ev]
  (swap! state assoc :mode :edit :app id))

(defn save-file [state id cm]
  (let [content (.getValue cm)]
    (js/console.log "New content:" content)
    (swap! state #(-> %
                      (dissoc :mode :app)
                      (assoc-in [:apps id :src] content)))))

(defn init-cm [state id dom-node]
  (aset (.-commands CodeMirror) "save" (partial save-file state id))
  (let [cm (CodeMirror
             dom-node
             #js {:lineNumbers true
                  :matchBrackets true
                  ;:autofocus true
                  :value (or (-> @state :apps (get id) :src) "")
                  :theme "erlang-dark"
                  :autoCloseBrackets true
                  :mode htmlmixed})]))

; ***** views ***** ;

(defn component-main [state]
  (let [apps (r/cursor state [:apps])
        mode (@state :mode)]
    [:div
     [:section#header
      [:div#logo
       [:img {:src "logo.svg"}]
       [:span "Slingcode"]
       ;[:nav "ipsum"]
       [:svg {:width "100%" :height "60px"}
        [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,52 100,0 50,-50 5000,0"}] 
        [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,57 103,0 50,-50 5000,0"}]]]]

     (if (= mode :edit)
       [:section#editor [:div.editor {:ref (partial init-cm state (@state :app))}]]
       [:section#apps
        [:section#tags
         [:ul
          [:li.active [:a {:href "#all"} "All"]]
          [:li [:a {:href "#examples"} "Examples"]]
          [:li [:a {:href "#templates"} "Templates"]]]]

        (for [[id app] @apps]
          [:div.app {:key id}
           [:div.columns {:on-click (partial open-app app)}
            [:div.column
             [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]]]
            [:div.column
             [:p.title (or (app :title) "Untitled app") [:span {:class "link-out"} [component-icon :link-out]]]
             [:p (app :description)]
             [:p.tags (for [t (app :tags)] [:span {:key t} t])]]]
           [:div.actions
            [:button {:on-click (partial edit-app state id)} [component-icon :code]]
            [:button [component-icon :share]]]])
        [:button#add-app {:on-click (partial add-app! apps)} "+"]])]))

; ***** init ***** ;

(defn reload! []
  (println "reload!")
  (let [state (local-storage (r/atom {}) :slingcode-state)]
    (js/console.log "Current state:" (clj->js @state))
    ;(reset! state {})
    (r/render [component-main state] (js/document.getElementById "app"))))

(defn main! []
  (println "main!")
  (reload!))
