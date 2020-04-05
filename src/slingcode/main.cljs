(ns slingcode.main
  (:require 
    [cljs.core.async :refer (chan put! close! <! go timeout) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]
    [alandipert.storage-atom :refer [local-storage]]
    [reagent.core :as r]
    [shadow.resource :as rc]
    [slingcode.icons :refer [component-icon]]
    ["localforage" :as localforage]
    ["codemirror" :as CodeMirror]
    ["codemirror/mode/htmlmixed/htmlmixed" :as htmlmixed]
    ["codemirror/mode/xml/xml" :as xml]
    ["codemirror/mode/css/css" :as css]
    ["codemirror/mode/javascript/javascript" :as javascript]))

(js/console.log "CodeMirror includes:" htmlmixed xml css javascript)

(defonce ui-state (atom {}))
(defonce dom-parser (js/DOMParser.))
(def uuid-re (js/RegExp. "([a-f0-9]+(-|$)){5}" "g"))

(def boilerplate (rc/inline "slingcode/boilerplate.html"))

; ***** data ***** ;

(defn make-boilerplate-files []
  [(js/File. #js [boilerplate] "index.html" #js {:type "text/html"})])

(defn extract-id [store-key]
  (let [id (.pop (.split store-key "/"))]
    (when (.match id uuid-re) [id store-key])))

(defn get-file-contents [file]
  "Wrapper hack to support older Chrome."
  (if file
    (if (aget file "text")
      (.text file)
      (js/Promise. (fn [res rej]
                     (let [fr (js/FileReader.)]
                       (aset fr "onload" (fn [done] (res (.-result fr))))
                       (.readAsText fr file)))))
    (js/Promise. (fn [res rej] (res "")))))

(defn get-apps-data [store]
  (go
    (let [store-keys (remove nil? (map extract-id (<p! (.keys store))))
          app-chans (when (not-empty store-keys)
                      (map (fn [[id store-key]]
                             (go
                               (let [files (<p! (.getItem store store-key))
                                     index-html (get files 0)
                                     src (<p! (get-file-contents index-html))
                                     dom (.parseFromString dom-parser src "text/html")
                                     title (.querySelector dom "title")
                                     title (if title (.-textContent title) "Untitled app")
                                     description (.querySelector dom "meta[name='description']")
                                     description (if description (.getAttribute description "content") "")
                                     tags (.querySelector dom "meta[name='slingcode-tags']")
                                     tags (remove #(= % "") (-> (if tags (.getAttribute tags "content") "") (.split ",")))
                                     app {id
                                          {:created (.-lastModified index-html)
                                           :title title
                                           :description description
                                           :tags (js->clj tags)}}]
                                 app)))
                           store-keys))
          result-chans (when app-chans (async/map merge app-chans))
          result (if result-chans (dissoc (<! result-chans) :skip) {})]
      result)))

; ***** functions ***** ;

(defn adblock-detect! [ui el]
  (when el
    (js/console.log (.-offsetHeight el))
    (if (= (.-offsetHeight el) 0)
      (swap! ui assoc :adblocked true))))

(defn attach-unload-event! [ui id win which]
  (.addEventListener win "unload"
                     (fn [ev]
                       (print "window unload" id which)
                       (js/console.log "unload window" win)
                       (js/console.log "unload window closed?" (aget win "closed"))
                       (js/console.log "unload window skipunload?" (aget win "skipunload"))
                       (if (nil? (aget win "skipunload"))
                         (swap! ui update-in [:windows] dissoc id)
                         (do
                           (js/setTimeout (fn []
                                            (attach-unload-event! ui id win)
                                            (aset win "skipunload" nil)) 100))))))

(defn attach-load-event! [ui id win]
  (.addEventListener win "load"
                     (fn [ev]
                       (attach-unload-event! ui id win "from-load")
                       ;(.addEventListener win "beforeunload" (fn [ev] (print "beforeunload")))
                       (print "window load" id))))

(defn save-handler! [{:keys [state ui store] :as app-data} id cm]
  (let [content (.getValue cm)
        win (-> @ui :windows (get id))
        ;doc (when win (.-document win))
        files [(js/File. #js [content] "index.html" #js {:type "text/html"})]]
    (go
      (js/console.log "Window:" win)
      ;(js/console.log "Document:" doc)
      ;(js/console.log "New content:" content)
      (when win
        (aset win "skipunload" true)
        ;(.addEventListener win "load" (fn [ev] (print "load catch A")))
        (-> win .-location (.replace (js/window.URL.createObjectURL (get files 0))))
        (print "window after setting location")
        ;(.addEventListener win "load" (fn [ev] (print "load catch B")))
        ;(js/setTimeout #(.addEventListener win "load" (fn [ev] (print "load catch D"))) 3)
        ;(.addEventListener (.-document win) "DOMContentLoaded" (fn [ev] (print "dom content loaded")))
        )
      ; TODO: catch exception and warn if disk full
      (<p! (.setItem store (str "app/" id) (clj->js files)))
      (swap! state assoc :apps (<! (get-apps-data store))))))

(defn create-editor! [dom-node src]
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
  (go
    (let [src (<p! (-> @state :files first get-file-contents))]
      (when (and dom-node (not (aget dom-node "CM")))
        (swap! ui assoc :editor (aset dom-node "CM" (create-editor! dom-node src)))))))

(defn launch-window! [ui id store]
  (if (@ui :adblocked)
    (go (js/alert "Sorry, Adblock won't let us open the window.\nTurn it off for this site to use Slingcode.") nil)
    (go
      (let [files (<p! (.getItem store (str "app/" id)))
            file (get files 0)
            url (js/window.URL.createObjectURL file)
            win (js/window.open url)]
        (attach-load-event! ui id win)
        win))))

; ***** events ***** ;

(defn open-app! [{:keys [state ui store] :as app-data} id ev]
  (.preventDefault ev)
  (go
    (let [w (-> @ui :windows (get id))
          w (if (not (and w (.-closed w))) w)
          w (or w (<! (launch-window! ui id store)))]
      (js/console.log "focusing on" w)
      (when w
        (.blur w)
        (.blur js/window)
        (js/setTimeout #(.focus w) 0)
        (swap! ui assoc-in [:windows id] w)))))

(defn edit-app! [{:keys [state ui store] :as app-data} id files ev]
  (.preventDefault ev)
  (go
    (let [files (or files (<p! (.getItem store (str "app/" id))))]
      (swap! state assoc :mode :edit :app id :files files))))

(defn close-editor! [state ev]
  (.preventDefault ev)
  (swap! state dissoc :mode :app :files))

(defn save-file! [{:keys [state ui] :as app-data} id ev]
  (.preventDefault ev)
  (save-handler! app-data id (@ui :editor)))

; ***** views ***** ;

(defn component-editor [{:keys [state ui] :as app-data}]
  [:section#editor
   [:ul#file-menu
    [:li [:a {:href "#" :on-click (partial close-editor! state)} "close"]]
    [:li [:a {:href "#" :on-click (partial save-file! app-data (@state :app))} "save"]]
    [:li [:a {:href "#" :on-click (partial open-app! app-data (@state :app))} "open"]]]
   [:ul#files
    (doall (for [f (@state :files)]
             [:li {:key (.-name f)} (.-name f)]))]
   [:div
    (doall (for [f (@state :files)]
             [:div.editor {:key (.-name f) :ref (partial init-cm! app-data (@state :app))}]))]])

(defn component-list-app [app-data id app]
  [:div.app
   [:div.columns
    [:div.column
     [:div [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]]] 
     [:button {:on-click (partial edit-app! app-data id nil)} [component-icon :code]]]
    [:div.column {:on-click (partial open-app! app-data id)}
     [:p.title (app :title) [:span {:class "link-out"} [component-icon :link-out]]]
     [:p (app :description)]
     [:p.tags (doall (for [t (app :tags)] [:span {:key t} t]))]]]
   ; [:div.actions [:button [component-icon :share]]]
   ])

(defn component-main [{:keys [state ui] :as app-data}]
  (let [apps (r/cursor state [:apps])
        mode (@state :mode)]
    [:div
     [:div#detect-adblock {:class "ads ad adsbox doubleclick ad-placement carbon-ads" :style {:height "1px"} :ref #(js/setTimeout (partial adblock-detect! ui %) 1)} " "]

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
          [:div {:key id} [component-list-app app-data id app]])

        [:button#add-app {:on-click (partial edit-app! app-data (random-uuid) (make-boilerplate-files))} "+"]])]))

; ***** init ***** ;

(defn render [app-data]
  (js/console.log "Current state:" (clj->js (deref (app-data :state)) (deref (app-data :ui))))
  (r/render [component-main app-data] (js/document.getElementById "app")))

(defn reload! []
  (println "reload!")
  (go
    (let [store (.createInstance localforage #js {:name "slingcode-apps"})
          app-data {:state (r/atom {:apps (<! (get-apps-data store))}) :ui ui-state :store store}
          {:keys [state ui]} app-data]
      (render app-data))))

(defn main! []
  (println "main!")
  (reload!))
