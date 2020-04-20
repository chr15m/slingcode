(ns slingcode.main
  (:require 
    [cljs.core.async :refer (chan put! close! <! go timeout) :as async]
    [cljs.core.async.interop :refer-macros [<p!]]
    [alandipert.storage-atom :refer [local-storage]]
    [reagent.core :as r]
    [reagent.dom :as rdom]
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
(def re-css-url (js/RegExp. "url\\([\"']{0,1}(.*?)[\"']{0,1}\\)" "gi"))
(def re-script-url (js/RegExp. "[\"'](.*?)[\"']" "gi"))

(def load-mode-qs "?view")

(def boilerplate (rc/inline "slingcode/boilerplate.html"))
(def not-found-app (rc/inline "slingcode/not-found.html"))
(def logo (rc/inline "slingcode/logo.svg"))
(def revision (rc/inline "slingcode/revision.txt"))
(def default-apps-base64-blob (rc/inline "default-apps.zip.b64"))
(def blocked-message {:level :warning
                      :text "We couldn't open the app window.\nSometimes adblockers mistakenly do this.\nTry disabling your adblocker\nfor this site and refresh."})

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

(defn get-valid-type [file]
  (or
    (let [t (.-type file)]
      (if (or (not t) (= t ""))
        (mime-types/lookup (.-name file))
        t))
    "application/octet-stream"))

; ***** functions ***** ;

(defn attach-unload-event! [ui id win which]
  (.addEventListener win "unload"
                     (fn [ev]
                       (js/console.log "unload window" id which win)
                       (swap! ui update-in [:windows] dissoc id))))

(defn attach-load-event! [ui id win]
  (.addEventListener win "load"
                     (fn [ev]
                       (attach-unload-event! ui id win "from-load")
                       (print "window load" id))))

(defn get-blob-url [file-blobs filename]
  (let [file (get file-blobs filename)]
    (when file
      (js/window.URL.createObjectURL file))))

(defn filter-valid-tags [file-blobs lookup tags]
  (->> tags
       (filter
         (fn [tag]
           (let [k (.getAttribute tag lookup)]
             (and k (get-blob-url file-blobs k)))))
       (vec)))

(defn replace-tag-attribute [file-blobs tag lookup]
  (let [tag-url (.getAttribute tag lookup)
        tag-url-patched (or (get-blob-url file-blobs tag-url) tag-url)]
    (.setAttribute tag lookup tag-url-patched)
    {tag-url [tag]}))

(defn replace-text-refs [file-blobs text regex]
  (clojure.string/replace
    text regex
    (fn [[full inner]]
      (let [matching-blob-url (get-blob-url file-blobs inner)]
        (if matching-blob-url
          (.replace full inner matching-blob-url)
          full)))))

(defn replace-tag-text [file-blobs tag regex]
  (aset tag "textContent"
        (replace-text-refs file-blobs
                           (aget tag "textContent")
                           regex)))

(defn replace-file-refs [file-blobs content-type regex]
  (go
    (let [blob-chans (map
                       (fn [[file-name file]]
                         (go
                           (if (= (.-type file) content-type)
                             (let [src (<p! (get-file-contents file :text))
                                   src (replace-text-refs file-blobs src regex)]
                               {file-name (js/File. (clj->js [src]) (.-name file) #js {:type (.-type file)})})
                             {file-name file})))
                       file-blobs)]
      (<! (async/map merge blob-chans)))))

(defn update-html-references! [files file]
  ; TODO: would be great to replace all of this with a serviceWorker
  ; which would return the blob when the filename is requested.
  ; Problem is that would introduce 1 extra file to the distribution
  ; and at the moment it's a pure single-HTML file.
  ; The user could progressively upgrade to that by simply including
  ; the service worker for items that fall through the cracks.
  ; This would make dynamic requests in user code possible.
  (go
    (let [src (<p! (get-file-contents file :text))
          file-blobs (into {} (map (fn [f] {(.-name f) f}) files))
          file-blobs (<! (replace-file-refs file-blobs "text/css" re-css-url))
          file-blobs (<! (replace-file-refs file-blobs "application/javascript" re-script-url))
          dom (.parseFromString dom-parser src "text/html")
          scripts (filter-valid-tags file-blobs "src" (js/Array.from (.querySelectorAll dom "script")))
          links (filter-valid-tags file-blobs "href" (js/Array.from (.querySelectorAll dom "link")))
          images (filter-valid-tags file-blobs "src" (js/Array.from (.querySelectorAll dom "img")))
          style-blocks (vec (js/Array.from (.querySelectorAll dom "style")))
          script-blocks (vec (filter #(not (.getAttribute % "src")) (js/Array.from (.querySelectorAll dom "script"))))]
      ; TODO: store references to tags replaced
      ; so when the original file is saved it can
      ; be live-reloaded in the document
      (doseq [s scripts]
        (replace-tag-attribute file-blobs s "src"))
      (doseq [l links]
        (replace-tag-attribute file-blobs l "href"))
      (doseq [i images]
        (replace-tag-attribute file-blobs i "src"))
      (doseq [sb style-blocks]
        (replace-tag-text file-blobs sb re-css-url))
      (doseq [sb script-blocks]
        (replace-tag-text file-blobs sb re-script-url))
      (let [updated-dom-string (str "<!DOCTYPE html>\n" (-> dom .-documentElement .-outerHTML))]
        [(js/File. (clj->js [updated-dom-string]) (.-name file) #js {:type (.-type file)})
         {:file-blobs file-blobs
          :links {:scripts scripts :links links}}]))))

(defn update-main-window-content! [{:keys [state ui store] :as app-data} files title id]
  (let [win (-> @ui :windows (get id))]
    (when win
      (go
        (let [frame (-> win .-document (.getElementById "result"))
              title-element (-> win .-document (.getElementsByTagName "title") js/Array.prototype.slice.call first)
              file (if files
                     (get-index-file files)
                     (js/File. #js [not-found-app] "index.html" #js {:type "text/html"}))
              [file references] (<! (update-html-references! files file))]
          (swap! state assoc-in [:editing :references] references)
          (aset frame "src" (js/window.URL.createObjectURL file))
          (aset title-element "textContent" (or title "Untitled app")))
        (print "window after updating content")))))

(defn save-handler! [{:keys [state ui store] :as app-data} id file-index cm]
  (let [content (when (and cm (aget cm "getValue")) (.getValue cm))
        files (get-in @state [:editing :files])
        files (vec (map-indexed (fn [i f]
                                  (if (and (= @file-index i) content)
                                    (js/File. #js [content] (.-name f) #js {:type (get-valid-type f)})
                                    f))
                                files))]
    (go
      ; TODO: catch exception and warn if disk full
      (<p! (.setItem store (str "app/" id) (clj->js files)))
      (let [apps (<! (get-apps-data store))]
        (swap! state 
               #(-> %
                    (assoc :apps apps)
                    (assoc-in [:editing :files] files))))
      (when (= (.-name (nth files @file-index)) "index.html")
        (let [dom (.parseFromString dom-parser content "text/html")
              title (.querySelector dom "title")
              title (if title (.-textContent title) "Untitled app")]
          (update-main-window-content! app-data files title id))))))

(defn remove-file! [{:keys [state ui store] :as app-data} app-id file-index]
  (let [files (get-in @state [:editing :files])
        files (vec (concat (subvec files 0 file-index) (subvec files (inc file-index))))]
    (go
      ; TODO: catch exception and warn if disk full
      (<p! (.setItem store (str "app/" app-id) (clj->js files)))
      (let [apps (<! (get-apps-data store))]
        (swap! state #(-> %
                          (assoc :apps apps)
                          (assoc-in [:editing :files] files)
                          (assoc-in [:editing :tab-index] (js/Math.max 0 (dec file-index)))))))))

(defn create-editor! [dom-node src content-type]
  (let [config {:lineNumbers true
                :matchBrackets true
                ;:autofocus true
                :value (or src "")
                :theme "erlang-dark"
                :autoCloseBrackets true
                :mode "text/plain"}
        config (if (= content-type "text/html")
                 (assoc config :mode "htmlmixed")
                 config)
        cm (CodeMirror
             dom-node
             (clj->js config))]
    cm))

(defn init-cm! [{:keys [state ui] :as app-data} id file-index tab-index dom-node]
  ; TODO: this function is called way more times than it needs to be - optimise
  (aset (.-commands CodeMirror) "save" (partial save-handler! app-data id tab-index))
  (go
    (let [files (-> @state :editing :files)]
      (if (< file-index (count files))
        (let [file (nth files file-index)
              src (<p! (get-file-contents file :text))]
          (when dom-node
            (let [cm (aget dom-node "CM")
                  cm (if cm
                       (do (.refresh cm) cm)
                       (aset dom-node "CM" (create-editor! dom-node src (get-valid-type file))))]
              (swap! state assoc-in [:editing :editors file-index] cm))))
        (swap! state update-in [:editing :editors] dissoc file-index)))))

(defn launch-window! [ui id]
  (let [win (js/window.open load-mode-qs (str "window-" id))]
    (attach-load-event! ui id win)
    win))

(defn is-view-mode [search-string]
  (= (.indexOf search-string load-mode-qs) 0))

(defn in-string [a b]
  (>= (.indexOf (if a (.toLowerCase a) "") b) 0))

(defn filter-search [apps search]
  (let [search (if search (.toLowerCase search) "")]
    (into {} (filter
               (fn [[k v]]
                 (or (= search "")
                     (nil? search)
                     (in-string (v :title) search)
                     (in-string (v :description) search)))
               apps))))

(defn add-apps! [state store apps-to-add]
  ; TODO: some kind of validation that this
  ; infact contains a reasonable web app
  (go
    (let [store-chans (map (fn [[n files]]
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
                         (update-main-window-content! app-data files title id)
                         (print "window load" id)))
    (when win
      (.focus win)
      (swap! ui assoc-in [:windows id] win))
    ; let the user know the window was blocked from opening
    (js/setTimeout
      (fn [] (when (aget win "closed")
               (swap! state assoc :message blocked-message)))
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

(defn delete-app! [{:keys [state ui store] :as app-data} id ev]
  (.preventDefault ev)
  (when (js/confirm "Are you sure you want to delete this app?")
    (close-editor! state ev)
    (go
      (<p! (.removeItem store (str "app/" id)))
      (swap! state assoc :apps (<! (get-apps-data store))))))

(defn delete-file! [{:keys [state ui store] :as app-data} app-id tab-index ev]
  (.preventDefault ev)
  (let [files (get-in @state [:editing :files])
        file (nth files @tab-index)]
    (if (= (.-name file) "index.html")
      (js/alert "The index.html file can't be deleted.\nDid you mean App->delete instead?")
      (when (js/confirm "Are you sure you want to delete this file?")
        (remove-file! app-data app-id @tab-index)))))

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

(defn increment-filename [f]
  (let [[_ file-part _ increment extension] (.exec #"(.*?)(-([0-9]+)){0,1}(?:\.([^.]+))?$" f)]
    (str file-part "-" (inc (int increment)) (and extension ".") extension)))

(defn ensure-unique-filename [files file-name]
  (let [file-names (set (map #(.-name %) files))]
    (loop [f file-name]
      (if (contains? file-names f)
        (recur (increment-filename f))
        f))))

(defn add-file! [{:keys [state store ui] :as app-data} ev]
  (.preventDefault ev)
  (let [files (js/Array.from (-> ev .-target .-files))
        file (first files)
        file-name (ensure-unique-filename (-> @state :editing :files) (.-name file))
        file-type (get-valid-type file)
        file (js/File. #js [file] file-name {:type file-type})]
    (swap! state
           #(-> %
                (update-in [:editing :files] conj file)
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
     [:span n]]))

(defn component-codemirror-block [{:keys [state ui] :as app-data} f i tab-index]
  [:div.editor
   {:style {:display (if (= i @tab-index) "block" "none")}
    :ref (partial init-cm! app-data (-> @state :editing :id) i tab-index)}])

(defn dropdown-menu-state [menu-state id]
  {:class (if (= @menu-state id) "open")
   :on-click #(swap! menu-state (fn [old-state] (if (= old-state id) nil id)))})

(defn component-editor [{:keys [state ui] :as app-data}]
  (let [files (r/cursor state [:editing :files])
        tab-index (r/cursor state [:editing :tab-index])
        menu-state (r/cursor state [:editing :menu-state])
        file-count (range (count @files))
        app-id (-> @state :editing :id)]
    [:section#editor.screen
     [:ul#file-menu {:on-mouse-leave #(reset! menu-state nil)}
      [:li.topmenu (dropdown-menu-state menu-state :app) "App"
       [:ul
        [:li (if (-> @ui :windows (get app-id))
               [:span "(launched)"]
               [:a {:href "#" :on-click (partial open-app! app-data app-id)} "launch"])]
        [:li [:a {:href "https://slingcode.net/publish" :target "_blank"} "publish"]]
        [:li [:a.color-warn {:href "#" :on-click (partial delete-app! app-data app-id)} "delete"]]
        [:li [:a {:href "#" :on-click (partial close-editor! state)} "close"]]]]
      [:li.topmenu (dropdown-menu-state menu-state :file) "File"
       [:ul
        [:li [:a {:href "#" :on-click (partial save-file! app-data tab-index app-id)} "save"]]
        ; [:li [:a.color-warn {:href "#"} "rename"]]
        [:li [:a.color-warn {:href "#" :on-click (partial delete-file! app-data app-id tab-index)} "delete"]]]]]
     [:ul#files
      (doall (for [i file-count]
               (let [f (nth @files i)]
                 (with-meta
                   [component-filename files i tab-index]
                   {:key (.-name f)}))))
      (when (< (count @files) 5)
        [:li.file-select [:input {:type "file"
                                  :name "add-file"
                                  :accept "image/*,text/*,application/json,application/javascript"
                                  :on-change (partial add-file! app-data)}] [:label "+"]])]
     [:div
      (doall (for [i file-count]
               (let [file (nth @files i)
                     filename (.-name file)
                     content-type (get-valid-type file)]
                 [:div {:key filename}
                  (cond
                    (= (.indexOf content-type "image/") 0) (when (= i @tab-index)
                                                              [:div.file-content [:img {:src (js/window.URL.createObjectURL file)}]])
                    :else [component-codemirror-block app-data file i tab-index])])))]]))

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

     (case mode
       :about [component-about state]
       :edit [component-editor app-data]
       :upload [component-upload app-data]
       nil [:section#apps.screen
            [:section#tags

             [:div#search
              [:input {:placeholder "Filter"
                       :on-change #(swap! state assoc :search (-> % .-target .-value))
                       :value (@state :search)}]
              [:span.icon-search [component-icon :search]]
              (when (and (@state :search) (not= (@state :search) ""))
                [:span.icon-times {:on-click #(swap! state dissoc :search)}
                 [component-icon :times]])]]

            (for [[id app] (filter-search @apps (@state :search))]
              [:div {:key id} [component-list-app app-data id app]])

            (when (@state :add-menu)
              [:div#add-menu
               [:ul
                [:li [:a {:href "#"
                          :on-click (partial edit-app! app-data (str (random-uuid)) (make-boilerplate-files))}
                      "New app"]]
                [:li.file-select [:input {:type "file"
                                          :name "upload-zip"
                                          :accept "application/zip"
                                          :on-change (partial initiate-zip-upload! app-data)}]
                 [:label "From zip"]]]])

            [:button#add-app {:on-click (partial toggle-add-menu! state)} (if (@state :add-menu) "x" "+")]])]))

(defn component-child-container []
  [:iframe#result])

; ***** init ***** ;

(defn render [app-data]
  (js/console.log "Current state:" (clj->js (deref (app-data :state)) (deref (app-data :ui))))
  (rdom/render [component-main app-data] (js/document.getElementById "app")))

(defn reload! []
  (println "reload!")
  (let [qs (-> js/document .-location .-search)]
    (if (is-view-mode qs)
      (rdom/render [component-child-container] (js/document.getElementById "app"))
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
