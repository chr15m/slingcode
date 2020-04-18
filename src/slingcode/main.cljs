(ns slingcode.main
  (:require 
    [cljs.core.async :refer (chan put! close! <! go timeout) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]
    [alandipert.storage-atom :refer [local-storage]]
    [reagent.core :as r]
    [shadow.resource :as rc]
    [slingcode.icons :refer [component-icon]]
    ["jszip" :as JSZip]
    ["localforage" :as localforage]
    ["mime-types" :as mime-types]
    ["codemirror" :as CodeMirror]
    ["codemirror/mode/htmlmixed/htmlmixed" :as htmlmixed]
    ["codemirror/mode/xml/xml" :as xml]
    ["codemirror/mode/css/css" :as css]
    ["codemirror/mode/javascript/javascript" :as javascript]))

(js/console.log "CodeMirror includes:" htmlmixed xml css javascript)

(defonce ui-state (r/atom {}))
(defonce dom-parser (js/DOMParser.))
(def re-uuid (js/RegExp. "([a-f0-9]+(-|$)){5}" "g"))
(def re-zip-app-files (js/RegExp. "(.*?)/(.*)"))

(def load-mode-qs "?view")

(def boilerplate (rc/inline "slingcode/boilerplate.html"))
(def not-found-app (rc/inline "slingcode/not-found.html"))
(def logo (rc/inline "slingcode/logo.svg"))
(def revision (rc/inline "slingcode/revision.txt"))
(def default-apps-base64-blob (rc/inline "default-apps.zip.b64"))

; ***** data ***** ;

(defn make-boilerplate-files []
  [(js/File. #js [boilerplate] "index.html" #js {:type "text/html"})])

(defn extract-id [store-key]
  (let [id (.pop (.split store-key "/"))]
    (when (.match id re-uuid) [id store-key])))

(defn get-index-file [files]
  (first (filter #(= (.-name %) "index.html") files)))

(defn get-file-contents [file result-type]
  "Wrapper hack to support older Chrome."
  (if file
    (if (aget file "text")
      (if (= result-type :array-buffer)
        (.arrayBuffer file)
        (.text file))
      (js/Promise. (fn [res rej]
                     (let [fr (js/FileReader.)]
                       (aset fr "onload" (fn [done] (res (.-result fr))))
                       (if (= result-type :array-buffer)
                         (.readAsArrayBuffer fr file)
                         (.readAsText fr file))))))
    (js/Promise. (fn [res rej] (res (if (= result-type :array-buffer) (js/ArrayBuffer.) ""))))))

(defn get-apps-data [store]
  (go
    (let [store-keys (remove nil? (map extract-id (<p! (.keys store))))
          app-chans (when (not-empty store-keys)
                      (map (fn [[id store-key]]
                             (go
                               (let [files (<p! (.getItem store store-key))
                                     index-html (get-index-file files)
                                     src (<p! (get-file-contents index-html :text))
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
                                           :tags (js->clj tags)
                                           :files (vec files)}}]
                                 app)))
                           store-keys))
          result-chans (when app-chans (async/map merge app-chans))
          result (if result-chans (dissoc (<! result-chans) :skip) {})]
      result)))

(defn make-slug [n]
  (-> n
      (.toLowerCase)
      (.replace (js/RegExp. "[^\\w ]+" "g") "")
      (.replace (js/RegExp. " +" "g") "-")))

(defn make-zip [store id title]
  (go
    (let [slug (make-slug title)
          zip (JSZip.)
          folder (.folder zip slug)
          files (<p! (.getItem store (str "app/" id)))
          blob-promises (.map files (fn [f] (get-file-contents f :array-buffer)))
          blobs (<p! (js/Promise.all blob-promises))]
      (doseq [i (range (count files))]
        (let [file (nth files i)
              blob (nth blobs i)]
          (.file folder (.-name file) blob)))
      (let [zip-blob (<p! (.generateAsync zip #js {:type "blob"}))
            zipfile (js/File. (clj->js [zip-blob]) (str slug ".zip") #js {:type "application/zip"})]
        zipfile))))

; ***** functions ***** ;

(defn adblock-detect! [ui el]
  (when (and el (= (.-offsetHeight el) 0))
    ;(js/console.log "BLOCKED")
    (swap! ui assoc :adblocked true)
    (print "Adblock detection:" (@ui :adblocked))))

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

(defn update-window-content! [{:keys [state ui store] :as app-data} files title id]
  (let [win (-> @ui :windows (get id))]
    (when win
        ;(aset win "skipunload" true)
        ;(.addEventListener win "load" (fn [ev] (print "load catch A")))
        ;(-> win .-location (.replace (js/window.URL.createObjectURL (get files 0))))
        (let [frame (-> win .-document (.getElementById "result"))
              title-element (-> win .-document (.getElementsByTagName "title") js/Array.prototype.slice.call first)
              file (if files (get-index-file files) (js/File. #js [not-found-app] "index.html" #js {:type "text/html"}))]
          (aset frame "src" (js/window.URL.createObjectURL file))
          (aset title-element "textContent" (or title "Untitled app")))
        (print "window after updating content")
        ;(.addEventListener win "load" (fn [ev] (print "load catch B")))
        ;(js/setTimeout #(.addEventListener win "load" (fn [ev] (print "load catch D"))) 3)
        ;(.addEventListener (.-document win) "DOMContentLoaded" (fn [ev] (print "dom content loaded")))
        )))

(defn save-handler! [{:keys [state ui store] :as app-data} id file-index cm]
  (let [content (when (and cm (aget cm "getValue")) (.getValue cm))
        files (get-in @state [:editing :files])
        files (vec (map-indexed (fn [i f]
                                  (if (and (= @file-index i) content)
                                    (js/File. #js [content] (.-name f) {:type (.-type f)})
                                    f))
                                files))]
    (go
      ; TODO: catch exception and warn if disk full
      (<p! (.setItem store (str "app/" id) (clj->js files)))
      (swap! state assoc :apps (<! (get-apps-data store)))
      (when (= (.-name (nth files @file-index)) "index.html")
        (let [dom (.parseFromString dom-parser content "text/html")
              title (.querySelector dom "title")
              title (if title (.-textContent title) "Untitled app")] 
          (update-window-content! app-data files title id))))))

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

(defn init-cm! [{:keys [state ui] :as app-data} id file-index tab-index dom-node]
  (aset (.-commands CodeMirror) "save" (partial save-handler! app-data id tab-index))
  (go
    (let [src (<p! (-> @state :editing :files (nth file-index) (get-file-contents :text)))]
      (when dom-node
        (let [cm (aget dom-node "CM")
              cm (if cm
                   (do (.refresh cm) cm)
                   (aset dom-node "CM" (create-editor! dom-node src)))]
          (swap! state assoc-in [:editing :editors file-index] cm))))))

(defn launch-window! [ui id]
  (let [win (js/window.open load-mode-qs (str "window-" id))]
    (attach-load-event! ui id win)
    ;(attach-unload-event! ui id win "from-load")
    win))

(defn is-view-mode [search-string]
  (= (.indexOf search-string load-mode-qs) 0))

(defn in [a b]
  (>= (.indexOf (if a (.toLowerCase a) "") b) 0))

(defn filter-search [apps search]
  (let [search (if search (.toLowerCase search) "")]
    (into {} (filter
               (fn [[k v]]
                 (or (= search "")
                     (nil? search)
                     (in (v :title) search)
                     (in (v :description) search)))
               apps))))

(defn add-apps! [state store apps-to-add]
  ; TODO: some kind of validation that this
  ; infact contains a reasonable web app
  (go
    (let [store-chans (map (fn [[n files]]
                             (js/console.log "Adding" n (clj->js files))
                             (go (let [id (str (random-uuid))]
                                   {id {:files (<p! (.setItem store (str "app/" id) (clj->js files)))}})))
                           apps-to-add)
          store-results (<! (async/map merge store-chans))]
      (swap! state assoc :apps (<! (get-apps-data store)))
      store-results)))

(defn zip-parse-extract-valid-dir-and-file
  [path & [file]]
  (let [[match rootdir filename] (.match path re-zip-app-files)]
    (when (and filename (not= filename "") (= (.indexOf filename "/") -1))
      [rootdir filename])))

(defn zip-parse-extract-file [file]
  (go
    (let [[folder filename] (zip-parse-extract-valid-dir-and-file (.-name file))
          zipped-file-blob (<p! (.async file "blob"))
          mime-type (or (mime-types/lookup filename) "application/octet-stream")]
      {folder [(js/File. (clj->js [zipped-file-blob])
                         filename
                         (clj->js {:type (or (mime-types/lookup filename) "application/octet-stream")}))]})))

(defn zip-extract [zip]
  (go
    (let [zipped-files (.filter zip zip-parse-extract-valid-dir-and-file)
          zipped-file-chans (map zip-parse-extract-file zipped-files)
          zipped-file-contents (<! (async/map
                                     (fn [& results]
                                       (apply merge-with (concat [into] results)))
                                     zipped-file-chans))]
      zipped-file-contents)))

(defn zip-parse-base64 [base64-blob]
  (go
    (let [z (JSZip.)
          zip (<p! (.loadAsync z base64-blob #js {:base64 true}))]
      (<! (zip-extract zip)))))

(defn zip-parse-file [file]
  (go
    (let [z (JSZip.)
          zip (<p! (.loadAsync z file))]
      (<! (zip-extract zip)))))

; ***** events ***** ;

(defn open-app! [{:keys [state ui store] :as app-data} id ev]
  (.preventDefault ev)
  (let [app (-> @state :apps (get id))
        files (if app (app :files))
        title (if app (app :title))
        win (-> @ui :windows (get id))
        win (if (not (and win (.-closed win))) win)
        win (or win (launch-window! ui id))]
    (.addEventListener win "load"
                       (fn [ev]
                         (update-window-content! app-data files title id)
                         (print "window load" id)))
    ;(js/console.log "focusing window" w)
    (when win
      ;(.blur w)
      ;(.blur js/window)
      (.focus win)
      ;(js/setTimeout #(.focus w) 0)
      (swap! ui assoc-in [:windows id] win))
    ; let the user know the window was blocked from opening
    (js/setTimeout
      (fn [] (when (aget win "closed")
               (swap! state assoc :message {:level :warning
                                            :text "We couldn't open the app window.\nSometimes adblockers mistakenly do this.\nTry disabling your adblocker\nfor this site and refresh."})))
      250)))

(defn edit-app! [{:keys [state ui store] :as app-data} id files ev]
  (.preventDefault ev)
  (go
    (let [files (vec (or files (<p! (.getItem store (str "app/" id)))))]
      (swap! state assoc :mode :edit :editing {:id id :files files :tab-index 0 :editors {}}))))

(defn close-editor! [state ev]
  (.preventDefault ev)
  (swap! state dissoc :mode :editing))

(defn save-file! [{:keys [state ui] :as app-data} tab-index app-id ev]
  (.preventDefault ev)
  (let [cm (get-in @state [:editing :editors @tab-index])]
    (save-handler! app-data app-id tab-index cm)))

(defn delete-file! [{:keys [state ui store] :as app-data} id ev]
  (.preventDefault ev)
  (when (js/confirm "Are you sure you want to delete this app?")
    (close-editor! state ev)
    (go
      (<p! (.removeItem store (str "app/" id)))
      (swap! state assoc :apps (<! (get-apps-data store))))))

(defn download-zip! [{:keys [state ui store] :as app-data} id title ev]
  (.preventDefault ev)
  (go
    (let [zipfile (<! (make-zip store id title))]
      (js/window.open (js/window.URL.createObjectURL zipfile)))))

(defn toggle-about-screen! [state ev]
  (.preventDefault ev)
  (swap! state update-in [:mode] (fn [mode] (if (not= mode :about) :about))))

(defn toggle-add-menu! [state ev]
  (.preventDefault ev)
  (swap! state update-in [:add-menu] not))

(defn initiate-zip-upload! [{:keys [state store ui] :as app-data} ev]
  (.preventDefault ev)
  (let [files (js/Array.from (-> ev .-target .-files))]
    (go (let [apps (<! (zip-parse-file (first files)))]
          ; TODO: some kind of interaction to tell the user what is in the file
          (let [added-apps (<! (add-apps! state store apps))]
            (if (= (count added-apps) 1)
              (edit-app! app-data (first (first added-apps)) nil ev)
              (swap! state assoc
                     :message {:level :success :text (str "Added " (count added-apps) " apps.")}
                     :add-menu nil)))))))

(defn add-file! [{:keys [state store ui] :as app-data} ev]
  (.preventDefault ev)
  (let [files (js/Array.from (-> ev .-target .-files))
        file (first files)
        file (if (.-type file)
               file
               (js/File. #js [file] (.-name file) {:type (mime-types/lookup (.-name file))}))]
    (swap! state
           #(-> %
                (update-in [:editing :files] conj (first files))
                (assoc-in [:editing :tab-index] (count (get-in % [:editing :files]))))))
  (let [app-id (get-in @state [:editing :id])
        tab-index (r/cursor state [:editing :tab-index])]
    (save-handler! app-data app-id tab-index ev)))

; ***** views ***** ;

(defn component-upload [{:keys [state ui] :as app-data}]
  [:div "Upload a zip file"
   [:button {:on-click #(swap! state dissoc :mode :edit)} "Ok"]])

(defn component-filename [files i tab-index]
  (let [active (= i @tab-index)
        file (nth @files i)
        n (.-name file)]
    [:li {:class (when active "active")
          :on-click #(reset! tab-index i)}
     [:span n]
     (comment
       "removing this renaming mechanism until i figure out
       a better data flow for it. probably just need a
       saner ui." (if (and (not= (.-name file) "index.html") active)
                    [:input {:key i
                             :value n
                             :style {:width (str (inc (.-length (or n ""))) "ch")}
                             :on-change (fn [ev]
                                          (print "new" (-> ev .-target .-value))
                                          ;(swap! names assoc-in [i] (-> ev .-target .-value))
                                          (swap! files assoc-in [i] (js/File. #js [file] (-> ev .-target .-value) {:type (.-type file)})))}]
                    [:span n]))]))

(defn component-codemirror-block [{:keys [state ui] :as app-data} f i tab-index]
  [:div.editor
   {:style {:display (if (= i @tab-index) "block" "none")}
    :ref (partial init-cm! app-data (-> @state :editing :id) i tab-index)}])

(defn component-editor [{:keys [state ui] :as app-data}]
  (let [files (r/cursor state [:editing :files])
        ;names (r/atom (vec (map #(.-name %) @files)))
        tab-index (r/cursor state [:editing :tab-index])
        file-count (range (count @files))
        app-id (-> @state :editing :id)]
    [:section#editor.screen
     [:ul#file-menu
      [:li [:a {:href "#" :on-click (partial close-editor! state)} "close"]]
      [:li [:a {:href "#" :on-click (partial save-file! app-data tab-index app-id)} "save"]]
      [:li [:a.color-warn {:href "#" :on-click (partial delete-file! app-data app-id)} "delete"]]
      [:li (if (-> @ui :windows (get app-id))
             [:span "(opened)"]
             [:a {:href "#" :on-click (partial open-app! app-data app-id)} "open"])]]
     [:ul#files
      (doall (for [i file-count]
               (let [f (nth @files i)]
                 (with-meta
                   [component-filename files i tab-index]
                   {:key (.-name f)}))))
      [:li.file-select [:input {:type "file"
                                :name "add-file"
                                :accept "image/*,text/*,application/json,application/javascript"
                                :on-change (partial add-file! app-data)}] [:label "+"]]]
     [:div
      (doall (for [i file-count]
               (let [file (nth @files i)]
                 [:div {:key (.-name file)}
                  (js/console.log (.-type file) (.-name file))
                  (cond
                    (= (.indexOf (.-type file) "text/") 0) [component-codemirror-block app-data file i tab-index]
                    (= (.indexOf (.-type file) "image/") 0) (when (= i @tab-index) [:div.file-content [:img {:src (js/window.URL.createObjectURL file)}]])
                    :else (when (= i @tab-index) [:div.file-content "Sorry, I don't know how to show this type of file."]))])))]]))

(defn component-list-app [{:keys [state ui] :as app-data} id app]
  [:div.app
   [:div.columns
    [:div.column
     [:div [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]]] 
     [:div [:button {:on-click (partial edit-app! app-data id nil) :title "Edit app"} [component-icon :code]]]
     [:div [:button {:on-click (partial download-zip! app-data id (app :title)) :title "Save zip"} [component-icon :download]]]]
    [:div.column
     [:a.title {:href (js/window.URL.createObjectURL (get-index-file (app :files)))
                :on-click (partial open-app! app-data id)
                :target (str "window-" id)}
      [:p.title (app :title) [:span {:class "link-out"} [component-icon :link-out]]]]
     [:p (app :description)]
     [:p.tags (doall (for [t (app :tags)] [:span {:key t} t]))]]]
   ; [:div.actions [:button [component-icon :share]]]
   ])

(defn component-about [state]
  [:section#about.screen
   [:p.title "Slingcode"]
   [:p "Personal computing platform."]
   [:p "Copyright Chris McCormick, 2020."]
   [:ul
    [:li [:a {:href "https://twitter.com/mccrmx"} "@mccrmx"]]
    [:li [:a {:href "https://mccormick.cx"} "mccormick.cx"]]]
   [:p.light "Revision: " revision]
   [:p [:a {:href "https://slingcode.net/" :target "_blank"} "slingcode.net"]]
   [:button {:on-click (partial toggle-about-screen! state)} "Ok"]])

(defn component-main [{:keys [state ui] :as app-data}]
  (let [apps (r/cursor state [:apps])
        mode (@state :mode)]
    [:div
     ;[:div#detect-adblock {:class "ads ad adsbox doubleclick ad-placement carbon-ads" :style {:height "1px"} :ref #(js/setTimeout (partial adblock-detect! ui %) 1)} " "]

     [:section#header
      [:div#logo
       [:img {:src (str "data:image/svg+xml;base64," (js/btoa logo))}]
       [:span "Slingcode"]
       [:nav [:a {:href "#" :on-click (partial toggle-about-screen! state)} "About"]]
       [:svg {:width "100%" :height "60px"}
        [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,52 100,0 50,-50 5000,0"}] 
        [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,57 103,0 50,-50 5000,0"}]]]]

     (when (@state :message)
       [:div.message-wrapper {:class (name (-> @state :message :level))}
        [:div.message {:on-click (fn [ev] (.preventDefault ev) (swap! state dissoc :message))}
         [component-icon :times] (-> @state :message :text)]])

     #_ (when (@ui :adblocked)
       [:pre.warning [:div.message "Adblocked."]])

     (case mode
       :about [component-about state]
       :edit [component-editor app-data]
       :upload [component-upload app-data]
       nil [:section#apps.screen
            [:section#tags
             #_ [:ul
                 [:li.active [:a {:href "#all"} "All"]]
                 [:li [:a {:href "#examples"} "Examples"]]
                 [:li [:a {:href "#templates"} "Templates"]]]

             [:div#search
              [:input {:placeholder "Filter" :on-change #(swap! state assoc :search (-> % .-target .-value)) :value (@state :search)}]
              [:span.icon-search [component-icon :search]]
              (when (and (@state :search) (not= (@state :search) ""))
                [:span.icon-times {:on-click #(swap! state dissoc :search)} [component-icon :times]])]]

            (for [[id app] (filter-search @apps (@state :search))]
              [:div {:key id} [component-list-app app-data id app]])

            (when (@state :add-menu)
              [:div#add-menu
               [:ul
                [:li [:a {:href "#" :on-click (partial edit-app! app-data (str (random-uuid)) (make-boilerplate-files))} "New app"]]
                [:li.file-select [:input {:type "file" :name "upload-zip" :accept "application/zip" :on-change (partial initiate-zip-upload! app-data)}] [:label "From zip"]]]])

            [:button#add-app {:on-click (partial toggle-add-menu! state)} (if (@state :add-menu) "x" "+")]])]))

(defn component-child-container []
  [:iframe#result])

; ***** init ***** ;

(defn render [app-data]
  (js/console.log "Current state:" (clj->js (deref (app-data :state)) (deref (app-data :ui))))
  (r/render [component-main app-data] (js/document.getElementById "app")))

(defn reload! []
  (println "reload!")
  (let [qs (-> js/document .-location .-search)]
    (if (is-view-mode qs)
      (r/render [component-child-container] (js/document.getElementById "app"))
      (go
        (let [store (.createInstance localforage #js {:name "slingcode-apps"})
              stored-apps (<! (get-apps-data store))
              state (r/atom {:apps stored-apps})
              app-data {:state state :ui ui-state :store store}
              first-run (nil? (js/localStorage.getItem "slingcode-has-run"))
              default-apps (<! (zip-parse-base64 default-apps-base64-blob))]
          (when (and first-run (= (count stored-apps) 0))
            (<! (add-apps! state store default-apps))
            (js/localStorage.setItem "slingcode-has-run" "true"))
          (js/console.log "Default apps:" (clj->js default-apps))
          (render app-data))))))

(defn main! []
  (println "main!")
  (reload!))
