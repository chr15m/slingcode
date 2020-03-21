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

(js/console.log "CodeMirror includes:" htmlmixed xml css javascript)

(defonce ui-state (atom {}))

(def boilerplate (rc/inline "slingcode/boilerplate.html"))

; ***** data ***** ;

(defn make-app []
  {:created (js/Date.)
   :src boilerplate
   :tags ["My apps"]})

; ***** functions ***** ;

(defn attach-unload-event [ui id win]
  (.addEventListener win "load"
                     (fn [ev]
                       (print "window load" id)
                       (.addEventListener win "unload"
                                          (fn [ev]
                                            (print "window unload" id)
                                            (swap! ui update-in [:windows] dissoc id))))))

(defn save-handler! [{:keys [state ui] :as app-data} id cm]
  (let [content (.getValue cm)
        win (-> @ui :windows (get id))
        doc (when win (.-document win))]
    (js/console.log "Window:" win)
    (js/console.log "Document:" doc)
    ;(js/console.log "New content:" content)
    (when doc
      (.open doc)
      (.write doc content)
      (attach-unload-event ui id win)
      (.close doc))
    (swap! state #(assoc-in % [:apps id :src] content))))

(defn create-editor [dom-node src]
  (js/console.log "create-editor")
  (let [cm (CodeMirror
             dom-node
             #js {:lineNumbers true
                  :matchBrackets true
                  ;:autofocus true
                  :value (or src "")
                  :theme "erlang-dark"
                  :autoCloseBrackets true
                  :mode "htmlmixed"})]
    cm))

(defn init-cm! [{:keys [state ui] :as app-data} id dom-node]
  (aset (.-commands CodeMirror) "save" (partial save-handler! app-data id))
  (let [src (-> @state :apps (get id) :src)]
    (when (and dom-node (not (aget dom-node "CM")))
      (swap! ui assoc
             :editor (aset dom-node "CM" (create-editor dom-node src))))))

(defn launch-window! [ui id src]
  (let [w (js/window.open (js/window.URL.createObjectURL (js/Blob. #js [src] #js {:type "text/html"})))]
    (attach-unload-event ui id w)
    w))

; ***** events ***** ;

(defn add-app! [apps ev]
  (.preventDefault ev)
  (swap! apps assoc (str (random-uuid)) (make-app)))

(defn open-app [{:keys [state ui] :as app-data} id ev]
  (.preventDefault ev)
  (let [src (or (-> @state :apps (get id) :src) "")
        w (-> @ui :windows (get id))
        w (or w (launch-window! ui id src))]
    (.focus w)
    (swap! ui assoc-in [:windows id] w)))

(defn edit-app [{:keys [state ui] :as app-data} id ev]
  (.preventDefault ev)
  (swap! state assoc :mode :edit :app id))

(defn close-editor [state ev]
  (.preventDefault ev)
  (swap! state dissoc :mode :app))

(defn save-file [{:keys [state ui] :as app-data} id ev]
  (.preventDefault ev)
  (save-handler! app-data id (@ui :editor)))

; ***** views ***** ;

(defn component-editor [{:keys [state ui] :as app-data}]
  [:section#editor
   [:ul#file-menu
    [:li [:a {:href "#" :on-click (partial close-editor state)} "close"]]
    [:li [:a {:href "#" :on-click (partial save-file app-data (@state :app))} "save"]]
    [:li [:a {:href "#" :on-click (partial open-app app-data (@state :app))} "open"]]]
   [:div.editor {:ref (partial init-cm! app-data (@state :app))}]])

(defn component-main [{:keys [state ui] :as app-data}]
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
       [component-editor app-data]
       [:section#apps
        [:section#tags
         [:ul
          [:li.active [:a {:href "#all"} "All"]]
          [:li [:a {:href "#examples"} "Examples"]]
          [:li [:a {:href "#templates"} "Templates"]]]]

        (for [[id app] @apps]
          [:div.app {:key id}
           [:div.columns {:on-click (partial open-app app-data id)}
            [:div.column
             [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]]]
            [:div.column
             [:p.title (or (app :title) "Untitled app") [:span {:class "link-out"} [component-icon :link-out]]]
             [:p (app :description)]
             [:p.tags (for [t (app :tags)] [:span {:key t} t])]]]
           [:div.actions
            [:button {:on-click (partial edit-app app-data id)} [component-icon :code]]
            [:button [component-icon :share]]]])
        [:button#add-app {:on-click (partial add-app! apps)} "+"]])]))

; ***** init ***** ;

(defn reload! []
  (println "reload!")
  (let [app-data {:state (local-storage (r/atom {}) :slingcode-state) :ui ui-state}
        {:keys [state ui]} app-data]
    (js/console.log "Current state:" (clj->js @state @ui))
    ;(reset! state {})
    (r/render [component-main app-data] (js/document.getElementById "app"))))

(defn main! []
  (println "main!")
  (reload!))
