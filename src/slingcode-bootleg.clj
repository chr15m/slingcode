(let [template (html "../build/index.html")
      css (slurp "../build/style.min.css")
      logo (slurp "../build/logo-b64-href.txt")
      js (slurp "../build/js/main.js")]
  (-> template
      (enlive/at [:link#style] (enlive/substitute (convert-to [:style css] :hickory)))
      (enlive/at [:link.rm] nil)
      (enlive/at [:link#favicon] (enlive/substitute (convert-to [:link {:rel "icon" :href logo}] :hickory)))
      (enlive/at [:script#entrypoint] (enlive/substitute (convert-to [:script js] :hickory)))))
