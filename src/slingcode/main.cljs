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
    ["codemirror/mode/javascript/javascript" :as javascript]
    ["@ungap/url-search-params" :as URLSearchParams]
    ["bugout" :as Bugout]
    ["tweetnacl" :as nacl]
    ["tweetnacl-auth" :as nacl-auth]
    ["bs58" :as bs58]
    ["niceware" :as niceware]
    ["webtorrent" :as webtorrent]
    ["./zxingwrap" :as zx]))

(js/console.log "CodeMirror includes:" htmlmixed xml css javascript)

(tap> "Slingcode start.")

(aset js/window "onerror" (fn [error] (tap> {"message" (aget error "message")
                                             "filename" (aget error "filename")
                                             "lineno" (aget error "lineno")
                                             "colno" (aget error "colno")
                                             "error" (js->clj (aget error "error"))})))

(defonce state (r/atom {}))
(def dom-parser (js/DOMParser.))
(def re-uuid (js/RegExp. "([a-f0-9]+(-|$)){5}" "g"))
(def re-zip-app-files (js/RegExp. "(.*?)/(.*)"))
(def re-css-url (js/RegExp. "url\\([\"']{0,1}(.*?)[\"']{0,1}\\)" "gi"))
(def re-script-url (js/RegExp. "[\"'](.*?)[\"']" "gi"))

(def boilerplate (rc/inline "slingcode/boilerplate.html"))
(def not-found-app (rc/inline "slingcode/not-found.html"))
(def resource-updater-source (rc/inline "resourceupdater.js"))
(def logo (rc/inline "slingcode/logo.svg"))
(def revision (rc/inline "slingcode/revision.txt"))
(def default-apps-base64-blob (rc/inline "default-apps.zip.b64"))
(def blocked-message {:level :warning
                      :text (clojure.string/join "\n"
                              ["We couldn't open the app window."
                               "Sometimes adblockers mistakenly do this."
                               "Try disabling your adblocker"
                               "for this site and refresh."])})
(def default-signaling-servers ["wss://hub.bugout.link"
                                "wss://tracker.openwebtorrent.com"
                                "wss://tracker.btorrent.xyz"])
(def default-settings {"signaling-servers" default-signaling-servers})

; only old Safari iOS needs this check
(def can-make-files
  (try
    (when (js/File. #js ["hello."] "hello.txt" #js {:type "text/plain"}) true)
    (catch :default e false)))

; check if this platform can do webtorrenting
(def can-p2p webtorrent/WEBRTC_SUPPORT)

(js/console.log "can-make-files?" can-make-files)
(js/console.log "can-p2p?" can-p2p)

; ***** functions ***** ;

(defn make-file [content file-name args]
  (let [blob-content (clj->js [content])
        last-modified (or (aget content "lastModified") (js/Date.))
        args (clj->js args)]
    (aset args "lastModified" last-modified)
    (if can-make-files
      (js/File. blob-content file-name args)
      (let [f (js/Blob. blob-content args)]
        (aset f "name" file-name)
        (aset f "lastModified" last-modified)
        f))))

(defn make-boilerplate-files []
  [(make-file boilerplate "index.html" {:type "text/html"})])

(defn extract-id [store-key]
  (let [id (.pop (.split store-key "/"))]
    (when (.match id re-uuid) [id store-key])))

(defn get-index-file [files]
  (first (filter #(= (.-name %) "index.html") files)))

(defn base64-to-blob [data-uri]
  ; TODO: remove this when old iPads pepper the netherworld
  (let [[header base64-string] (.split data-uri ",")
        byte-string (js/atob base64-string)
        content-type (-> header (.split ":") second (.split ";") first)
        ab (js/ArrayBuffer. (.-length byte-string))
        ia (js/Uint8Array. ab)]
    (doseq [i (range (.-length byte-string))]
      (aset ia i (.charCodeAt byte-string i)))
    (js/Blob. (clj->js [ab]) (clj->js {:type content-type}))))

(defn get-file-contents [file result-type]
  (if file
    (if (aget file "text")
      (case result-type
        :array-buffer (.arrayBuffer file)
        :binary-string (.arrayBuffer file)
        (.text file))
      ; wrapper hack to support older chrome versions
      ; TODO: remove when support is good across browsers
      (js/Promise. (fn [res rej]
                     (let [fr (js/FileReader.)]
                       (aset fr "onload" (fn [done] (res (.-result fr))))
                       (case result-type
                         :array-buffer (.readAsArrayBuffer fr file)
                         :binary-string (.readAsBinaryString fr file)
                         :data-url (.readAsDataURL fr file)
                         (.readAsText fr file))))))
    (js/Promise. (fn [res rej] (res (if (= result-type :array-buffer) (js/ArrayBuffer. []) ""))))))

(defn store-files! [store app-id files]
  (if can-make-files
    ; TODO: warn user if storage full or error writing
    (.setItem store (str "app/" app-id) (clj->js files))
    ; all of the following complex serialization and deserialization
    ; is neccessary because of the way iOS Safari 9 + localForage
    ; handle Files embedded within a deeper structure
    ; TODO: remove when all browsers support localForage blob arrays
    (js/Promise.
      (fn [res err]
        (go
          (let [file-chans (map (fn [f]
                                  (go
                                    (let [content {:name (.-name f)
                                                   :type (.-type f)
                                                   :lastModified (.-lastModified f)
                                                   :content (<p! (get-file-contents f :data-url))}]
                                      [content]))) files)
                files (<! (async/map concat file-chans))]
            (res (<p! (.setItem store (str "app/" app-id) (clj->js files))))))))))

(defn retrieve-files [store app-id]
  (if can-make-files
    (.getItem store (str "app/" app-id))
    ; Safari why (TODO: remove when fn section above is removed)
    (js/Promise.
      (fn [res err]
        (go
          (let [files (js->clj (<p! (.getItem store (str "app/" app-id))))]
            (res (map #(make-file (base64-to-blob (get % "content")) (get % "name") {:type (get % "type")}) files))))))))

(defn store-app-order! [store app-order]
  (js/Promise.
    (fn [res err]
      (go
        (res (js->clj (<p! (.setItem store "order" (clj->js app-order)))))))))

(defn retrieve-app-order [store]
  (js/Promise.
    (fn [res err]
      (go
        (res (js->clj (<p! (.getItem store "order"))))))))

(defn ensure-app-order [app-order stored-apps]
  (let [not-in-app-order (->> stored-apps
                              (filter (fn [[app-id files]] (not (some #(= app-id %) app-order))))
                              (map first)
                              vec)]
    (vec (concat (vec (or app-order [])) not-in-app-order))))

(defn get-files-map [files]
  (into {} (map (fn [f] {(.-name f) f}) files)))

(defn extract-icon-url [dom files]
  (let [files-map (get-files-map files)
        icon-url (.querySelector dom "link[rel*='icon']")
        icon-url (if icon-url (.getAttribute icon-url "href"))
        icon-file (get files-map icon-url)
        icon-url (if icon-file (js/window.URL.createObjectURL icon-file) icon-url)]
    icon-url))

(defn get-apps-data [store]
  (go
    (let [store-keys (remove nil? (map extract-id (<p! (.keys store))))
          app-chans (when (not-empty store-keys)
                      (map (fn [[id store-key]]
                             (go
                               (let [files (<p! (retrieve-files store id))
                                     index-html (get-index-file files)
                                     src (<p! (get-file-contents index-html :text))
                                     dom (.parseFromString dom-parser src "text/html")
                                     title (.querySelector dom "title")
                                     title (if title (.-textContent title) "Untitled app")
                                     icon-url (extract-icon-url dom files)
                                     description (.querySelector dom "meta[name='description']")
                                     description (if description (.getAttribute description "content") "")
                                     tags (.querySelector dom "meta[name='slingcode-tags']")
                                     tags (remove #(= % "") (-> (if tags (.getAttribute tags "content") "") (.split ",")))
                                     app {id
                                          {:title title
                                           :description description
                                           :tags (js->clj tags)
                                           :files (vec files)
                                           :icon-url icon-url}}]
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
          files (<p! (retrieve-files store id))
          blob-promises (.map files (fn [f] (get-file-contents f :array-buffer)))
          blobs (<p! (js/Promise.all blob-promises))]
      (doseq [i (range (count files))]
        (let [file (nth files i)
              blob (nth blobs i)]
          (.file folder (.-name file) blob)))
      (let [zip-blob (<p! (.generateAsync zip #js {:type "blob"}))
            zipfile (make-file zip-blob (str slug ".zip") {:type "application/zip"})]
        zipfile))))

(defn get-valid-type [file]
  (or
    (let [t (.-type file)]
      (if (or (not t) (= t ""))
        (mime-types/lookup (.-name file))
        t))
    "application/octet-stream"))

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
        tag-url-patched (or (get-blob-url file-blobs tag-url) tag-url)
        reference-id (str (random-uuid))]
    (.setAttribute tag lookup tag-url-patched)
    (.setAttribute tag "data-slingcode-reference" reference-id)
    {tag-url [reference-id]}))

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

(defn replace-file-refs [file-blobs file-name file content-type regex]
  (go
    (if (= (.-type file) content-type)
      (let [src (<p! (get-file-contents file :text))
            src (replace-text-refs file-blobs src regex)]
        {file-name (make-file src (.-name file) {:type (.-type file)})})
      {file-name file})))

(defn replace-file-blobs-refs [file-blobs content-type regex]
  (go
    (let [blob-chans (map (fn [[file-name file]] (replace-file-refs file-blobs file-name file content-type regex)) file-blobs)]
      (<! (async/map merge blob-chans)))))

(defn update-html-references [files file]
  ; TODO: would be great to replace all of this with a serviceWorker
  ; which would return the blob when the filename is requested.
  ; Problem is that would introduce 1 extra file to the distribution
  ; and at the moment it's a pure single-HTML file.
  ; The user could progressively upgrade to that by simply including
  ; the service worker for items that fall through the cracks.
  ; This would make dynamic requests in user code possible.
  (go
    (let [src (<p! (get-file-contents file :text))
          file-blobs (get-files-map files)
          file-blobs (<! (replace-file-blobs-refs file-blobs "text/css" re-css-url))
          file-blobs (<! (replace-file-blobs-refs file-blobs "application/javascript" re-script-url))
          dom (.parseFromString dom-parser src "text/html")
          html (.querySelector dom "html")
          scripts (filter-valid-tags file-blobs "src" (js/Array.from (.querySelectorAll dom "script")))
          links (filter-valid-tags file-blobs "href" (js/Array.from (.querySelectorAll dom "link")))
          images (filter-valid-tags file-blobs "src" (js/Array.from (.querySelectorAll dom "img")))
          style-blocks (vec (js/Array.from (.querySelectorAll dom "style")))
          script-blocks (vec (filter #(not (.getAttribute % "src")) (js/Array.from (.querySelectorAll dom "script"))))
          script-references (merge-with into (doall (map #(replace-tag-attribute file-blobs % "src") scripts)))
          style-references (merge-with into (doall (map #(replace-tag-attribute file-blobs % "href") links)))]
      (doseq [i images]
        (replace-tag-attribute file-blobs i "src"))
      (doseq [sb style-blocks]
        (replace-tag-text file-blobs sb re-css-url))
      (doseq [sb script-blocks]
        (replace-tag-text file-blobs sb re-script-url))
      (let [resource-updater (.createElement dom "script")]
        (aset resource-updater "textContent" (str resource-updater-source))
        (.appendChild html resource-updater))
      (let [updated-dom-string (str "<!DOCTYPE html>\n" (-> dom .-documentElement .-outerHTML))]
        [(make-file updated-dom-string (.-name file) {:type (.-type file)})
         {:file-blobs file-blobs
          :tags {:scripts script-references
                 :links style-references}}]))))

(defn set-main-window-content! [state document files index-file]
  (go
    (let [content (<p! (get-file-contents index-file :text))
          dom (.parseFromString dom-parser content "text/html")
          title (.querySelector dom "title")
          title (if title (.-textContent title) "Untitled app")
          icon-url (extract-icon-url dom files)
          frame (-> document (.getElementById "slingcode-frame"))
          title-element (-> document (.getElementsByTagName "title") js/Array.prototype.slice.call first)
          icon-element (-> document (.querySelector "link[rel*='icon']"))
          [index-file references] (<! (update-html-references files index-file))]
      (aset title-element "textContent" (or title "Untitled app"))
      (when icon-url (.setAttribute icon-element "href" icon-url)) 
      (aset frame "src" (js/window.URL.createObjectURL index-file))
      (swap! state #(-> % (assoc-in [:editing :references] references))))))

(defn update-main-window-content! [{:keys [state store] :as app-data} files app-id win]
  (js/console.log "updating main window content")
  (let [file (if files
               (get-index-file files)
               (make-file not-found-app "index.html" {:type "text/html"}))]
    (js/console.log "updating main window content (window)" win)
    (when win
      (set-main-window-content! state (-> win .-document) files file))))

(defn update-refs! [{:keys [state store] :as app-data} files app-id file-index]
  (go
    (let [win (-> @state :windows (get app-id))
          frame (when win (-> win .-document (.getElementById "slingcode-frame")))
          [index-file updated-references] (<! (update-html-references files (get-index-file files)))
          links (get-in @state [:editing :references :tags])
          ;file-blobs (get-in @state [:editing :references :file-blobs])
          file-blobs (updated-references :file-blobs)
          original-file (nth files file-index)
          file (get file-blobs (.-name original-file))]
      (when (and frame links)
        (doseq [k (keys links)
                references (links k)
                [file-name reference-ids] references
                reference-id reference-ids]
          (js/console.log "Updating index.html refs" file-name (.-name file) reference-id)
          (when (= file-name (.-name file))
            (.postMessage (.-contentWindow frame)
                          (clj->js {"reference" reference-id
                                    "kind" (.substr (name k) 0 (dec (count (name k))))
                                    "url" (js/window.URL.createObjectURL file)})
                          "*")))))))

(defn save-handler! [{:keys [state store] :as app-data} app-id file-index cm]
  (let [content (when (and cm (aget cm "getValue")) (.getValue cm))
        files (get-in @state [:editing :files])
        files (vec (map-indexed (fn [i f]
                                  (if (and (= @file-index i) content)
                                    (make-file content (.-name f) {:type (get-valid-type f)})
                                    f))
                                files))]
    (go
      (<p! (store-files! store app-id files))
      (let [apps (<! (get-apps-data store))
            file (nth files @file-index)
            win (-> @state :windows (get app-id))
            app-order (vec (@state :app-order))
            app-order (if (some #(= app-id %) app-order)
                        app-order
                        (conj (or app-order []) app-id))
            app-order (<p! (store-app-order! store (vec app-order)))]
        (swap! state 
               #(-> %
                    (assoc :apps apps)
                    (assoc :app-order app-order)
                    (assoc-in [:editing :files] files)))
        (if (= (.-name file) "index.html")
          (update-main-window-content! app-data files app-id win)
          (update-refs! app-data files app-id @file-index))))))

(defn remove-file! [{:keys [state store] :as app-data} app-id file-index]
  (let [files (get-in @state [:editing :files])
        files (vec (concat (subvec files 0 file-index) (subvec files (inc file-index))))]
    (go
      (<p! (store-files! store app-id files))
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

(defn init-cm! [{:keys [state] :as app-data} id file-index tab-index dom-node]
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
              (.focus cm)
              (swap! state assoc-in [:editing :editors file-index] cm))))
        (swap! state update-in [:editing :editors] dissoc file-index)))))

(defn in-string [a b]
  (>= (.indexOf (if a (.toLowerCase a) "") b) 0))

(defn filter-search [apps search]
  (let [search (if search (.toLowerCase search) "")]
    (into {} (filter
               (fn [[k v]]
                 (or (= search "")
                     (nil? search)
                     (in-string (.toLowerCase (or (v :title) "")) search)
                     (in-string (.toLowerCase (or (v :description) "")) search)))
               apps))))

(defn add-apps! [state store apps-to-add]
  ; TODO: some kind of validation that this
  ; infact contains a reasonable web app
  (go
    (let [store-chans (map (fn [[n files]]
                             (go (let [id (str (random-uuid))
                                       files (<p! (store-files! store id files))]
                                   [[id {:files files}]])))
                           apps-to-add)
          store-results (<! (async/map concat store-chans))
          app-order (vec (concat (or (vec (@state :app-order)) []) (vec (map first store-results))))]
      (swap! state assoc
             :apps (<! (get-apps-data store))
             :app-order (<p! (store-app-order! store app-order)))
      store-results)))

(defn zip-parse-extract-valid-dir-and-file [path]
  (let [[match rootdir filename] (.match path re-zip-app-files)]
    (when (and filename (not= filename "") (= (.indexOf filename "/") -1))
      [rootdir filename])))

(defn zip-parse-extract-file [file]
  (go
    (let [[folder filename] (zip-parse-extract-valid-dir-and-file (.-name file))
          zipped-file-blob (<p! (.async file "blob"))
          mime-type (or (mime-types/lookup filename) "application/octet-stream")
          updated-file (make-file zipped-file-blob
                                  filename
                                  {:type (or (mime-types/lookup filename) "application/octet-stream")})]
      {folder [updated-file]})))

(defn zip-extract [zip]
  (go
    (let [zipped-files (.filter zip zip-parse-extract-valid-dir-and-file)
          zipped-folder-names (set (map #(first (zip-parse-extract-valid-dir-and-file (.-name %))) zipped-files))
          zipped-file-chans (map zip-parse-extract-file zipped-files)
          zipped-file-contents (<! (async/map
                                     (fn [& results]
                                       (apply merge-with (concat [into] results)))
                                     zipped-file-chans))
          zipped-file-contents-sorted (map (fn [folder] [folder (get zipped-file-contents folder)]) zipped-folder-names)]
      ; we want the apps to appear top to bottom as they
      ; are in a zip file so we reverse the order
      ; otherwise the one at the top would get added first
      (reverse zipped-file-contents-sorted))))

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

; ***** event handlers ***** ;

(defn open-app! [{:keys [state store] :as app-data} id ev]
  (.preventDefault ev)
  (let [win (-> @state :windows (get id))
        win (if (not (and win (.-closed win))) win)
        win (or win (js/window.open (str "?app=" id) (str "window-" id)))]
    (when win
      (.focus win)
      (swap! state assoc-in [:windows id] win))
    ; let the user know if the window was blocked from opening
    (js/setTimeout
      (fn [] (when (aget win "closed")
               (swap! state assoc :message blocked-message)))
      250)))

(defn edit-app! [{:keys [state store history] :as app-data} app-id files ev]
  (when ev
    (.preventDefault ev))
  (go
    (let [files (vec (or files (<p! (retrieve-files store app-id))))]
      (when (not (and ev (aget ev "state")))
        (.pushState history #js {"mode" "edit" "app-id" app-id} "Edit" (str "?edit=" app-id)))
      (swap! state assoc :mode :edit :editing {:id app-id :files files :tab-index 0 :editors {}}))))

(defn go-home! [{:keys [state history base-url]} ev]
  (.preventDefault ev)
  (swap! state dissoc :mode :editing)
  (when (not (and ev (aget ev "state")))
    (.pushState history #js {} "" base-url)))

(defn save-file! [{:keys [state] :as app-data} tab-index app-id ev]
  (.preventDefault ev)
  (let [cm (get-in @state [:editing :editors @tab-index])]
    (save-handler! app-data app-id tab-index cm)))

(defn delete-app! [{:keys [state store] :as app-data} id ev]
  (.preventDefault ev)
  (when (js/confirm "Are you sure you want to delete this app?")
    (go-home! app-data ev)
    (go
      (<p! (.removeItem store (str "app/" id)))
      (let [app-order (@state :app-order)
            app-order (filter #(not= % id) app-order)
            app-order (<p! (store-app-order! store app-order))]
        (swap! state assoc
               :apps (<! (get-apps-data store))
               :app-order app-order)))))

(defn delete-file! [{:keys [state store] :as app-data} app-id tab-index ev]
  (.preventDefault ev)
  (let [files (get-in @state [:editing :files])
        file (nth files @tab-index)]
    (if (= (.-name file) "index.html")
      (js/alert "The index.html file can't be deleted.\nDid you mean App->delete instead?")
      (when (js/confirm "Are you sure you want to delete this file?")
        (remove-file! app-data app-id @tab-index)))))

(defn download-zip! [{:keys [state store] :as app-data} id title ev]
  (.preventDefault ev)
  (go
    (let [zipfile (<! (make-zip store id title))]
      (swap! state assoc :mode :download :zipfile zipfile))))

(defn add-zip-file! [{:keys [state store] :as app-data} zipfile]
  ; TODO: some kind of interaction to tell the user what is in the file
  (go (let [apps (<! (zip-parse-file zipfile))
            added-apps (<! (add-apps! state store apps))
            app (first added-apps)]
        (if (= (count added-apps) 1)
          (edit-app! app-data (first app) (-> app second :files) nil)
          (swap! state assoc
                 :message {:level :success :text (str "Added " (count added-apps) " apps.")}
                 :add-menu nil)))))

(defn toggle-screen! [state which ev]
  (.preventDefault ev)
  (swap! state
         (fn [s]
           (let [mode (s :mode)
                 mode-last (s :mode-last)
                 s (if (= mode which)
                     (-> s
                         (assoc :mode mode-last)
                         (dissoc :mode-last))
                     (assoc s :mode which :mode-last mode))]
             (dissoc s :burger-menu)))))

(defn toggle-app-actions-menu! [state app-id]
  (swap! state update-in [:actions-menu] #(if (= % app-id) nil app-id)))

(defn toggle-add-menu! [state ev]
  (.preventDefault ev)
  (swap! state update-in [:add-menu] not))

(defn initiate-zip-upload! [{:keys [state store] :as app-data} ev]
  (.preventDefault ev)
  (let [files (js/Array.from (-> ev .-target .-files))]
    (add-zip-file! app-data (first files))))

(defn increment-filename [f]
  (let [[_ file-part _ increment extension] (.exec #"(.*?)(-([0-9]+)){0,1}(?:\.([^.]+))?$" f)]
    (str file-part "-" (inc (int increment)) (and extension ".") extension)))

(defn ensure-unique-filename [files file-name]
  (let [file-names (set (map #(.-name %) files))]
    (loop [f file-name]
      (if (contains? file-names f)
        (recur (increment-filename f))
        f))))

(defn add-file! [{:keys [state store] :as app-data} file]
  (swap! state
         #(-> %
              (update-in [:editing :files] conj file)
              (assoc-in [:editing :tab-index] (count (get-in % [:editing :files])))))
  (let [app-id (get-in @state [:editing :id])
        tab-index (r/cursor state [:editing :tab-index])]
    (save-handler! app-data app-id tab-index nil)))

(defn add-selected-file! [{:keys [state store] :as app-data} ev]
  (.preventDefault ev)
  (let [files (js/Array.from (-> ev .-target .-files))
        file (first files)
        file-name (ensure-unique-filename (-> @state :editing :files) (.-name file))
        file-type (get-valid-type file)
        file (make-file file file-name {:type file-type})]
    (add-file! app-data file)))

(defn create-empty-file! [{:keys [state store] :as app-data} ev]
  (.preventDefault ev)
  (let [file-name (js/prompt "Filename:")
        file (when (and file-name (not= file-name ""))
               (make-file "" file-name {:type (or (mime-types/lookup file-name) "text/plain")}))]
    (when file
      (add-file! app-data file))))

(defn reset-slingcode! [store ev]
  (.preventDefault ev)
  (when (js/confirm
          "WARNING!\nCompletely reset Slingcode and delete all apps?")
    (go
      (<p! (.clear store))
      (-> js/window .-location .reload))))

(defn check-for-new-version! [state ev]
  (.preventDefault ev)
  (swap! state assoc :update-check :checking)
  (go
    (let [result (<p! (.then (js/fetch "https://slingcode.net/revision.txt") #(.text %)))]
      (js/console.log "checked" result)
      (swap! state assoc :update-check result))))

(defn switch-tab! [editor tab-index i ev]
  (.preventDefault ev)
  (reset! tab-index i))

(defn toggle-burger-menu! [burger-menu ev]
  (.preventDefault ev)
  (swap! burger-menu not))

(defn remove-signaling-server! [original-settings s ev]
  (.preventDefault ev)
  (swap! original-settings update-in ["signaling-servers"]
         (fn [servers]
           (vec 
             (keep-indexed
               (fn [idx server] (when (not= idx s) server))
               servers)))))

(defn save-settings! [{:keys [state store] :as app-data} original-settings ev]
  (js/console.log "Yes hello")
  (.preventDefault ev)
  (go
    (js/console.log "And here" (clj->js @original-settings))
    (<p! (.setItem store "slingcode-settings" (clj->js @original-settings)))
    (swap! state assoc :settings @original-settings)
    (js/console.log "saved")
    (toggle-screen! state :settings ev)))

; ***** send / receive ***** ;

(defn room-name-from-secret [secret]
  (clojure.string/join " "
                       (concat
                         ["slingcode" "exchange"]
                         (niceware/bytesToPassphrase (.slice (nacl/hash (nacl/hash secret)) 0 16)))))

(defn extract-torrent-file [torrent]
  (let [c (chan)
        f (aget (.-files torrent) 0)]
    (go
      (.getBlob f (fn [err blob]
                    (put! c blob)
                    (close! c))))
    c))

(defn seed-webtorrent! [bugout-instance f title]
  (let [webtorrent-instance (.-wt bugout-instance)
        announce (clj->js {"announce" (.-announce bugout-instance)})
        encrypted-file (make-file f (make-slug title) #js {:type "application/octet-stream"})
        c (chan)]
    (js/console.log "webtorrent instance" webtorrent-instance announce)
    (.on webtorrent-instance "error" (fn [err] (js/console.log "WebTorrent Error" err)))
    (go
      (.seed webtorrent-instance
             encrypted-file
             announce
             (fn [torrent]
               (js/console.log "torrent seeded" torrent)
               (put! c torrent)
               (close! c))))
    c))

(defn one-time-secret-first-half [bugout-address-raw hmac-key]
  (.slice (nacl-auth bugout-address-raw hmac-key) 0 4))

(defn stop-sending-receiving! [{:keys [state store] :as app-data} mode ev]
  (when ev (.preventDefault ev))
  (let [bugout (get-in @state [mode :bugout-instance])
        webtorrent (when bugout (aget bugout "wt"))]
    (when bugout
      (.close bugout)
      (.destroy webtorrent)))
  (swap! state dissoc mode (when ev :mode))
  (js/console.log "stop-sending-receiving!"))

(defn receive-app! [{:keys [state store] :as app-data} human-readable-one-time-secret ev]
  (when ev (.preventDefault ev))
  (swap! state assoc :mode :receive :receive {:status {:initiated true}})
  (when can-p2p
    (let [human-readable-one-time-secret (vec (.split human-readable-one-time-secret " "))
          passphrase-tokens (filter #(and % (not= % "")) human-readable-one-time-secret)
          one-time-secret (niceware/passphraseToBytes (clj->js passphrase-tokens))
          bugout-address-hash-prefix (.slice one-time-secret 0 4)
          bugout-secret (.slice one-time-secret 4 8)
          hmac-key (nacl/hash bugout-secret)
          room-name (room-name-from-secret one-time-secret)
          bugout-instance (Bugout. room-name (clj->js {:announce (get-in @state [:settings "signaling-servers"])}))
          webtorrent-instance (.-wt bugout-instance)]
      (swap! state assoc-in [:receive :bugout-instance] bugout-instance)
      (.on bugout-instance "seen"
           (fn [address]
             (.send bugout-instance address (clj->js {"secret" human-readable-one-time-secret}))
             (swap! state assoc-in [:receive :status :seen] true)))
      (.on bugout-instance "message"
           (fn [address message]
             (let [torrent-hash (aget message "torrent-hash")
                   encryption-key (js/Uint8Array.from (aget message "encryption-key"))
                   encryption-nonce (js/Uint8Array.from (aget message "encryption-nonce"))
                   bugout-address-raw (bs58/decode address)
                   bugout-address-hash-prefix-check (one-time-secret-first-half bugout-address-raw hmac-key)]
               (when (and torrent-hash
                          encryption-key
                          encryption-nonce
                          (nacl/verify bugout-address-hash-prefix bugout-address-hash-prefix-check))
                 (.add webtorrent-instance
                       (str "magnet:?xt=urn:btih:" torrent-hash)
                       (clj->js {"announce" (.-announce bugout-instance)})
                       (fn [torrent]
                         (swap! state assoc-in [:receive :status :downloading] true)
                         (.on torrent "done"
                              (fn []
                                (.send bugout-instance address (clj->js {"secret" human-readable-one-time-secret
                                                                         "done" true}))
                                (swap! state assoc-in [:receive :status :done] true)
                                (go
                                  (let [file-name (str (aget (first (.-files torrent)) "name") ".zip")
                                        encrypted-zip (<! (extract-torrent-file torrent))
                                        encrypted-array-buffer (<p! (get-file-contents encrypted-zip :array-buffer))
                                        encrypted-zipfile-buffer (js/Uint8Array. encrypted-array-buffer)
                                        zipfile-array (.open nacl/secretbox encrypted-zipfile-buffer encryption-nonce encryption-key)
                                        zipfile-buffer (.-buffer zipfile-array)
                                        zipfile (make-file zipfile-buffer file-name #js {:type "application/zip"})]
                                    (js/console.log "stopping receive")
                                    (stop-sending-receiving! app-data :receive ev) 
                                    (js/console.log "adding zip")
                                    (add-zip-file! app-data zipfile))))))))))))))

(defn send-app! [{:keys [state store] :as app-data} app-id files title ev]
  (.preventDefault ev)
  (swap! state assoc :mode :send)
  (when can-p2p
    (go
      (let [bugout-secret (.randomBytes nacl 4)
            hmac-key (nacl/hash bugout-secret)
            bugout-keypair (nacl/sign.keyPair)
            bugout-address (Bugout/address (aget bugout-keypair "publicKey"))
            bugout-address-raw (bs58/decode bugout-address)
            bugout-address-hash-prefix (one-time-secret-first-half bugout-address-raw hmac-key)
            one-time-secret (let [u (js/Uint8Array. 8)]
                              (.set u bugout-address-hash-prefix)
                              (.set u bugout-secret 4)
                              u)
            human-readable-one-time-secret (vec (niceware/bytesToPassphrase one-time-secret))
            room-name (room-name-from-secret one-time-secret)
            encryption-key (.randomBytes nacl 32) ; used to encrypt the zip
            encryption-nonce (.randomBytes nacl 24)
            zipfile (<! (make-zip store app-id title))
            zipfile-buffer (js/Uint8Array. (<p! (get-file-contents zipfile :array-buffer)))
            encrypted-zipfile (.secretbox nacl zipfile-buffer encryption-nonce encryption-key)
            ; TODO: use settings from config page
            bugout-instance (Bugout. room-name (clj->js {:keyPair bugout-keypair :announce (get-in @state [:settings "signaling-servers"])}))
            torrent (<! (seed-webtorrent! bugout-instance encrypted-zipfile title))]

        (js/console.log "bugout ready" bugout-instance)

        (.on bugout-instance "seen"
             (fn [address]
               (swap! state assoc-in [:send :status :seen] true)))

        (.on bugout-instance "message"
             (fn [address message]
               (let [secret (vec (aget message "secret"))
                     done (aget message "done")]
                 (when (= secret human-readable-one-time-secret)
                   (if done
                     (swap! state assoc-in [:send :status :done] true)
                     (do
                       (.send bugout-instance address (clj->js {"encryption-key" (js/Array.from encryption-key)
                                                                "encryption-nonce" (js/Array.from encryption-nonce)
                                                                "torrent-hash" (.-infoHash torrent)}))
                       (swap! state assoc-in [:send :status :replied] true)))))))

        (.on torrent "upload"
             (fn [byte-count]
               (swap! state assoc-in [:send :status :sending] true)))

        (swap! state assoc :send {:title title
                                  :secret human-readable-one-time-secret
                                  :bugout-instance bugout-instance
                                  :status {}})))))

(defn enable-scan-camera! [{:keys [state store] :as app-data} el]
  (js/console.log "enable-scan-camera!" el)
  (if el
    (let [scanner (zx/lib.BrowserQRCodeReader.)]
      (aset js/window "slingcode-qr-scanner" scanner)
      (.decodeFromInputVideoDeviceContinuously
        scanner
        js/undefined
        "qrcam"
        (fn [result err]
          (when result
            (.reset scanner)
            (js/console.log result)
            (let [text (aget result "text")
                  qs (.pop (.split text "?"))
                  qs-params (URLSearchParams. qs)
                  secret (.get qs-params "receive")]
              (if secret
                (receive-app! app-data secret nil)))))))
    (let [scanner (aget js/window "slingcode-qr-scanner")]
      (when scanner
        (.stopContinuousDecode scanner)
        (.stopAsyncDecode scanner)
        (.stopStreams scanner)
        (.reset scanner)))))

; ***** views ***** ;

(defn component-no-p2p [state]
  [:section#send.screen
       [:p "Sorry, your browser doesn't support peer-to-peer WebRTC connections."]
       [:button {:on-click #(swap! state dissoc :mode)} "Ok"]])

(defn component-receive [{:keys [state] :as app-data}]
  (let [secret (r/cursor state [:receive :secret])
        receive-scan (r/cursor state [:receive :scan])
        status (or (get-in @state [:receive :status]) {})
        bugout (get-in @state [:receive :bugout-instance])
        completed-class {:class "completed"}]
    (if can-p2p
      (if (status :initiated)
        [:section#send.screen
         [:p (str "Ready to receive.")]
         (when (not (status :done)) [:div#send-spinner "Receiving..."])
         [:ul
          [:li completed-class "Connection listening."]
          [:li (when (status :seen) completed-class) "Seen other device."]
          [:li (when (status :downloading) completed-class) "Downloading the app."]
          [:li (when (status :done) completed-class) "Done."]]
         [:button {:on-click (partial stop-sending-receiving! app-data :receive)} (if (status :done) "Ok" "Cancel")]]
        [:section#send.screen
         [:p "Enter the 'send secret' from the other device, or scan the QR code, to start receiving."]
         [:div.qr
          (if @receive-scan
            [:div
             [:p "Scan QR to receive app."]
             [:video {:id "qrcam" :ref (partial enable-scan-camera! app-data)}]]
            [:input#send-secret {:value @secret
                                 :placeholder "Enter 'send secret'..."
                                 :on-change #(reset! secret (-> % .-target .-value))}])
          [:div.input-group
           [:button {:on-click #(swap! receive-scan not)} (if @receive-scan "Input 'send secret'" "Scan a QR code")]]]
         [:div.input-group
          (when (not @receive-scan) [:button {:on-click (partial receive-app! app-data @secret)} "Receive"])
          [:button {:on-click (partial stop-sending-receiving! app-data :receive)} "Cancel"]]])
      [component-no-p2p state])))

(defn render-qr-code [secret-phrase base-url el]
  (js/console.log "render-qr-code" secret-phrase el)
  (when (and el (= (.-length (.-children el)) 0))
    (let [code-writer (zx/lib.BrowserQRCodeSvgWriter.)]
      (.writeToDom code-writer el
                   (str base-url
                        "?receive="
                        (js/encodeURIComponent secret-phrase))
                   300 300))))

(defn component-secret [secret secret-field base-url]
  (when secret
    (let [secret-phrase (clojure.string/join " " secret)]
      [:div.secret-container
       [:p "Select 'receive' on your other device."]
       [:p "Then enter or scan this 'send secret' to connect:"]
       [:input#send-secret {:value secret-phrase :read-only true :ref #(reset! secret-field %)}]
       [:button#copy {:on-click (fn [ev]
                                  (.select @secret-field)
                                  (.execCommand js/document "copy")
                                  (js/alert "Send secret copied!"))} "Copy"]
       [:div.qr
        [:div#qrcode {:ref (partial render-qr-code secret-phrase base-url)}]
        [:p "scan to receive"]]])))

(defn component-send [{:keys [state base-url] :as app-data}]
  (let [status (or (get-in @state [:send :status]) {})
        bugout (get-in @state [:send :bugout-instance])
        secret (get-in @state [:send :secret])
        secret-field (r/atom nil)
        title (get-in @state [:send :title])
        completed-class {:class "completed"}]
    (if can-p2p
      [:section#send.screen
       (if bugout
         [:div
          [:p [:strong (str "Ready to send" (when title (str " '" title "'")) ".")]]
          [component-secret secret secret-field base-url]
          (when (not (status :done)) [:div#send-spinner "Sending..."])
          [:ul
           [:li completed-class "Connection listening."]
           [:li (when (status :seen) completed-class) "Seen other device."]
           [:li (when (status :replied) completed-class) "Replied to request."]
           [:li (when (status :sending) completed-class) "Sending data."]
           [:li (when (status :done) completed-class) "Done."]]
          [:button {:on-click (partial stop-sending-receiving! app-data :send)} (if (status :done) "Ok" "Cancel")]]
         [:div
          [:p "Preparing files for sharing."]
          [:p#send-spinner "Preparing..."]
          [:button {:on-click (partial stop-sending-receiving! app-data :send)} "Cancel"]])]
      [component-no-p2p state])))

(defn component-upload [{:keys [state] :as app-data}]
  [:div "Load a zip file"
   [:button {:on-click #(swap! state dissoc :mode :edit)} "Ok"]])

(defn component-filename [editor files i tab-index]
  (let [active (= i @tab-index)
        file (nth @files i)
        n (.-name file)]
    [:li {:class (when active "active")
          :on-click (partial switch-tab! editor tab-index i)}
     [:span n]]))

(defn component-codemirror-block [{:keys [state] :as app-data} app-id f i tab-index]
  [:div.editor
   {:style {:display (if (= i @tab-index) "block" "none")}
    :ref (partial init-cm! app-data app-id i tab-index)}])

(defn dropdown-menu-state [menu-state id]
  {:class (if (= @menu-state id) "open")
   :on-click #(swap! menu-state (fn [old-state] (if (= old-state id) nil id)))})

(defn file-load-li [app-data uniq]
  [:li
   [:input {:type "file"
            :id (str "add-file-" uniq)
            :accept "image/*,text/*,application/json,application/javascript"
            :on-change (partial add-selected-file! app-data)}]
   [:label {:for (str "add-file-" uniq)} "Load"]])

(defn file-create-li [app-data]
  [:li {:on-click (partial create-empty-file! app-data)} "Create"])

(defn component-editor [{:keys [state] :as app-data}]
  (let [files (r/cursor state [:editing :files])
        tab-index (r/cursor state [:editing :tab-index])
        menu-state (r/cursor state [:editing :menu-state])
        file-count (range (count @files))
        app-id (-> @state :editing :id)
        app-window (-> @state :windows (get app-id))]
    [:section#editor.screen
     [:ul#file-menu {:on-mouse-leave #(reset! menu-state nil)}
      [:li.topmenu (dropdown-menu-state menu-state :app) "App"
       [:ul
        [:li (if (and app-window (not (aget app-window "closed")))
               [:span "(launched)"]
               [:a {:href "#" :on-click (partial open-app! app-data app-id)} "Launch"])]
        [:li [:a {:href "https://slingcode.net/publish" :target "_blank"} "Publish"]]
        [:li [:a.color-warn {:href "#" :on-click (partial delete-app! app-data app-id)} "Delete"]]
        [:li {:on-click (partial go-home! app-data)} "Close"]]]
      [:li.topmenu (dropdown-menu-state menu-state :file) "File"
       [:ul
        [:li [:a {:href "#" :on-click (partial save-file! app-data tab-index app-id)} "Save"]]
        ; [:li [:a.color-warn {:href "#"} "rename"]]
        [:li [:a.color-warn {:href "#" :on-click (partial delete-file! app-data app-id tab-index)} "Delete"]]
        [file-load-li app-data "top"]
        [file-create-li app-data]]]]
     [:ul#files
      (doall (for [i file-count]
               (let [f (nth @files i)
                     editor (get-in @state [:editing :editors i])]
                 (with-meta
                   [component-filename editor files i tab-index]
                   {:key (.-name f)}))))
      (when (< (count @files) 7)
        [:li.add-file-menu.topmenu (merge {:on-mouse-leave #(reset! menu-state nil)}
                                          (dropdown-menu-state menu-state :add-file)) "+"
         [:ul
          [file-load-li app-data "sub"]
          [file-create-li app-data]]])]
     [:div
      (doall (for [i file-count]
               (let [file (nth @files i)
                     filename (.-name file)
                     content-type (get-valid-type file)]
                 [:div {:key filename}
                  (cond
                    (= (.indexOf content-type "image/") 0) (when (= i @tab-index)
                                                             [:div.file-content [:img {:src (js/window.URL.createObjectURL file)}]])
                    :else [component-codemirror-block app-data app-id file i tab-index])])))]]))

(defn component-list-app [{:keys [state] :as app-data} app-id app]
  [:div.app
   [:div.columns
    [:div.column
     [:div {:on-click (partial open-app! app-data app-id)}
      (if (app :icon-url)
        [:img.app-icon {:src (app :icon-url)}]
        [:svg {:width 64 :height 64} [:circle {:cx 32 :cy 32 :r 32 :fill "#555"}]])]
     [:div [:button {:on-click (partial edit-app! app-data app-id nil) :title "Edit app code"} [component-icon :code]]]
     (when (= (@state :actions-menu) app-id)
       [:div.app-actions-menu {:on-mouse-leave (partial toggle-app-actions-menu! state nil)}
        [:div [:button {:on-click (partial send-app! app-data app-id (app :files) (app :title)) :title "Send app"} [component-icon :paper-plane]]]
        [:div [:button {:on-click (partial edit-app! app-data (str (random-uuid)) (app :files)) :title "Clone app"} [component-icon :clone]]]
        [:div [:button {:on-click (partial download-zip! app-data app-id (app :title)) :title "Save app zip"} [component-icon :download]]]])
     [:div [:button {:on-click (partial toggle-app-actions-menu! state app-id) :title "App actions"} [component-icon :bars]]]]
    [:div.column
     [:a.title {:href (str "?app=" app-id)
                :on-click (partial open-app! app-data app-id)
                :target (str "window-" app-id)}
      [:p.title (app :title) [:span {:class "link-out"} [component-icon :link-out]]]]
     [:p (app :description)]]]])

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
   [:button {:on-click (partial toggle-screen! state :about)} "Ok"]
   [:div#dedication "For S & O."]])

(defn component-settings [{:keys [state store] :as app-data} original-settings update-check-status]
  (let [signaling-servers (get @original-settings "signaling-servers")
        update-check (@state :update-check)]
    (if (not (nil? update-check))
      [:section#settings.screen
       [:p.title "Check for updates"]
       (if (= update-check :checking)
         [:div#loading "Checking..."]
         (if (= update-check revision)
           [:div [:p "You have the latest version already."]]
           [:div
            [:p "Revision " update-check " of " [:a {:href "https://slingcode.net/slingcode.html" :download "slingcode.html"} "slingcode.html"] " is available."]
            [:p "(Right click and 'Save link as' to download it)."]
            [:p "Your revision is " revision]]))
       [:div.input-group
        [:button {:on-click #(swap! state dissoc :update-check)} (if (= update-check :checking) "Cancel" "Ok")]]]
      [:section#settings.screen
       [:p.title "Settings"]
       [:div.input-group
        [:p "Update & reset"]
        [:ul
         [:li [:button.success {:on-click (partial check-for-new-version! state)} "Check for update"]]
         [:li [:button.warning {:on-click (partial reset-slingcode! store)} "Reset Slingcode"]]]]
       [:div.input-group
        [:p "WebTorrent signaling servers"]
        [:ul#signaling-servers
         (for [s (range (count signaling-servers))]
           [:li {:key s}
            [:button.remove {:on-click (partial remove-signaling-server! original-settings s)} "x"]
            [:input {:value (nth signaling-servers s)
                     :on-change #(swap! original-settings assoc-in ["signaling-servers" s] (-> % .-target .-value))}]])
         [:li [:button.success
               {:on-click #(swap! original-settings update-in ["signaling-servers"] conj "wss://")} "+"]]]
        [:p
         "(Learn how to " [:a {:href "https://github.com/webtorrent/bittorrent-tracker/"
                               :target "_blank"} "run your own signaling server"]
         ".)"]]
       [:div.input-group
        [:button {:on-click (partial save-settings! app-data original-settings)} "Ok"]
        [:button {:on-click (partial toggle-screen! state :settings)} "Cancel"]]])))

(defn component-download [state]
  (let [zipfile (@state :zipfile)]
    [:section#about.screen
     [:p.title "Download"]
     [:p [:a {:href (js/window.URL.createObjectURL zipfile) :download (.-name zipfile)} (.-name zipfile)]]
     [:button {:on-click #(swap! state dissoc :mode :zipfile)} "Done"]]))

(defn component-main [{:keys [state] :as app-data}]
  (let [apps (r/cursor state [:apps])
        burger-menu (r/cursor state [:burger-menu])
        app-order (@state :app-order)
        mode (@state :mode)]
    [:div
     [:section#header
      [:div#logo
       [:img {:src (str "data:image/svg+xml;base64," (js/btoa logo))}]
       [:span "Slingcode"]
       [:nav (when (nil? mode) [:a {:href "#" :on-click (partial toggle-burger-menu! burger-menu)} [component-icon :bars]])]
       (when @burger-menu
         [:ul#burger-menu
          [:li [:a {:href "#" :on-click (partial toggle-screen! state :settings)} "Settings"]]
          [:li [:a {:href "#" :on-click (partial toggle-screen! state :about)} "About"]]])
       [:svg#lines {:width "100%" :height "60px"}
        [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,52 100,0 50,-50 5000,0"}] 
        [:path {:fill-opacity 0 :stroke-width 2 :stroke-linecap "round" :stroke-linejoin "round" :d "m 0,57 103,0 50,-50 5000,0"}]]]]

     (when (@state :message)
       [:div.message-wrapper {:class (name (-> @state :message :level))}
        [:div.message {:on-click (fn [ev] (.preventDefault ev) (swap! state dissoc :message))}
         [component-icon :times] (-> @state :message :text)]])

     (case mode
       :about [component-about state]
       :settings [component-settings app-data (r/atom (get-in @state [:settings]))]
       :edit [component-editor app-data]
       :upload [component-upload app-data]
       :send [component-send app-data]
       :receive [component-receive app-data]
       :download [component-download state]
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

            (let [apps-filtered (filter-search @apps (@state :search))]
              (for [id (reverse app-order)]
                (let [app (get apps-filtered id)]
                  (when app
                    [:div {:key id} [component-list-app app-data id app]]))))

            (when (@state :add-menu)
              [:div#add-menu {:on-mouse-leave (partial toggle-add-menu! state)}
               [:ul
                [:li [:a {:href "#"
                          :on-click (partial edit-app! app-data (str (random-uuid)) (make-boilerplate-files))}
                      "New app"]]
                [:li [:input {:type "file"
                              :name "upload-zip"
                              :accept "application/zip"
                              :on-change (partial initiate-zip-upload! app-data)}]
                 [:label "Upload zip"]]
                [:li [:a {:href "#"
                          :on-click (fn [ev] (.preventDefault ev) (swap! state assoc :mode :receive))}
                      "Receive app"]]]])

            [:button#add-app {:on-click (partial toggle-add-menu! state)} (if (@state :add-menu) "x" "+")]])]))

(defn component-child-container [{:keys [state query] :as app-data} files file app-id]
  [:iframe#slingcode-frame
   {:ref (fn [el]
           ; if we weren't spawned by a slingcode editor then load our own content
           (let [parent (.-opener js/window)]
             (if parent
               (.postMessage parent #js {:action "reload" :app-id app-id} "*")
               (set-main-window-content! state js/document files file))))}])

; ***** browser message handlers ***** ;

(defn receive-message! [{:keys [state store] :as app-data} message]
  (js/console.log "received message" message)
  (let [action (aget message "data" "action")
        app-id (aget message "data" "app-id")]
    (cond (= action "reload")
          (go
            (let [files (<p! (retrieve-files store app-id))]
              (js/console.log "refreshin'"
                              app-id
                              (aget message "origin")
                              (-> js/document .-location .-href))
              (update-main-window-content! app-data files app-id (.-source message))
              (swap! state update-in [:windows] assoc app-id (.-source message))))
          (= action "unload")
          (swap! state update-in [:windows] dissoc app-id))))

(defn receive-popstate! [{:keys [state] :as app-data} ev]
  (js/console.log "popstate" (.-state ev))
  (let [popstate (.-state ev)
        mode (aget popstate "mode")
        app-id (aget popstate "app-id")]
    (case mode
      "edit" (edit-app! app-data app-id nil ev)
      (go-home! app-data ev))))

; ***** init ***** ;

(defn reload! []
  (println "reload!")
  (let [qs (-> js/document .-location .-search)
        qs-params (URLSearchParams. qs)
        history (.-history js/window)
        location (-> js/window .-location)
        base-url (str (.-protocol location) "//" (.-host location) (.-pathname location))]
    (go
      (let [store (.createInstance localforage #js {:name "slingcode-apps"})
            app-data {:state state :store store :base-url base-url :history history}
            el (js/document.getElementById "app") 
            ;_ (tap> {"CLEARED STORE" (<p! (.clear store))})
            stored-apps (<! (get-apps-data store))
            app-order (<p! (retrieve-app-order store))
            app-order (ensure-app-order (or app-order []) stored-apps)
            settings (or (js->clj (<p! (.getItem store "slingcode-settings")))
                         default-settings)]
        (swap! state assoc
               :apps stored-apps
               :app-order app-order
               :settings settings)
        (if (.has qs-params "app")
          (let [app-id (.get qs-params "app")
                files (<p! (retrieve-files store app-id))
                file (get-index-file files)]
            (.addEventListener js/window "beforeunload"
                               (fn [ev]
                                 (let [parent (.-opener js/window)]
                                   (when parent
                                     (.postMessage parent #js {:action "unload" :app-id app-id})))))
            (rdom/render [component-child-container app-data files file app-id] el))
          (let [first-run (nil? (<p! (.getItem store "slingcode-has-run")))
                default-apps (<! (zip-parse-base64 default-apps-base64-blob))
                old-message-callback (aget js/window "message-callback")
                old-popstate-callback (aget js/window "popstate-callback")
                receive-code (.get qs-params "receive")
                edit-app-id (.get qs-params "edit")]
            (when old-message-callback
              (.removeEventListener js/window "message" old-message-callback))
            (when old-popstate-callback
              (.removeEventListener js/window "popstate" old-popstate-callback))
            (.addEventListener js/window "popstate"
                               (aset js/window "popstate-callback" (partial receive-popstate! app-data)))
            (.addEventListener js/window "message"
                               (aset js/window "message-callback" (partial receive-message! app-data)))
            (when (and first-run (= (count stored-apps) 0))
              (when (<! (add-apps! state store default-apps))
                (<p! (.setItem store "slingcode-has-run" "true"))))
            (js/console.log "Default apps:" (clj->js default-apps))
            (js/console.log "Current state:" (clj->js (deref (app-data :state))))
            (tap> {"apps" ((deref (app-data :state)) :apps)})
            (if receive-code
              (do
                (.replaceState history #js {"mode" "home"} (.-title js/document) base-url)
                (receive-app! app-data receive-code nil))
              (if edit-app-id
                (edit-app! app-data edit-app-id nil nil)))
            (rdom/render [component-main app-data] el)))))))

(defn main! []
  (println "main!")
  (reload!))
