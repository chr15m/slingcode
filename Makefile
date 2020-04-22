STATIC=index.html style.css logo.png
BUILD=build/js/main.js $(foreach S, $(STATIC), build/$(S))
SITEFILES=public/style.css public/img/computers-in-our-lives.jpg public/img/appleIIe.jpg public/logo.svg public/logo.png
SITEFILES_DEST=$(foreach S, $(SITEFILES), slingcode.net/$(S))

# bootleg stuff
BOOTLEGVERSION=0.1.7
BOOTLEG=./bin/bootleg-$(BOOTLEGVERSION)

slingcode.net: slingcode.net/index.html slingcode.net/publish.html slingcode.net/slingcode.html slingcode.net/license.txt

slingcode.net/slingcode.html: $(BOOTLEG) $(BUILD) build/logo-b64-href.txt build/style.min.css src/slingcode/revision.txt
	$(BOOTLEG) src/slingcode-bootleg.clj > build/slingcode-compiled.html
	npx minify build/slingcode-compiled.html > $@

slingcode.net/index.html: src/slingcode-site-bootleg.clj README.md src/slingcode-static.html $(SITEFILES_DEST)
	$(BOOTLEG) src/slingcode-site-bootleg.clj README.md > $@

slingcode.net/publish.html: src/slingcode-site-bootleg.clj publish.md src/slingcode-static.html $(SITEFILES_DEST)
	$(BOOTLEG) src/slingcode-site-bootleg.clj publish.md > $@

slingcode.net/public/%: public/%
	@mkdir -p `dirname $@`
	cp $< $@

slingcode.net/license.txt: license.txt
	cp $< $@

build/logo-b64-href.txt: build/logo.png
	echo "data:image/png;base64,"`base64 $< -w0` > $@

build/style.min.css: build/style.css
	npx minify $< > $@

build/js/main.js: $(shell find src) node_modules shadow-cljs.edn src/default-apps.zip.b64
	npx shadow-cljs release app

build/style.css: public/*.css
	cat public/codemirror.css public/erlang-dark.css public/style.css > $@

build/%: public/%
	@mkdir -p `dirname $@`
	cp $< $@

src/default-apps.zip.b64: src/default-apps.zip
	base64 $< > $@

src/default-apps.zip: $(shell find src/default-apps)
	rm -f $@
	cd src/default-apps && zip -r ../default-apps.zip .

node_modules: package.json
	npm i
	touch node_modules

BOOTLEGTAR=bootleg-$(BOOTLEGVERSION)-linux-amd64.tgz
BOOTLEGURL=https://github.com/retrogradeorbit/bootleg/releases/download/v${BOOTLEGVERSION}/${BOOTLEGTAR}

$(BOOTLEG):
	@echo "Installing bootleg."
	mkdir -p bin
	wget $(BOOTLEGURL) -O $(BOOTLEGTAR)
	tar -zxvf $(BOOTLEGTAR) && mv bootleg $(BOOTLEG)
	rm $(BOOTLEGTAR)

src/slingcode/revision.txt:
	git rev-parse HEAD | cut -b -16 > $@

# dev targets

.PHONY: watch clean

watch: src/slingcode/revision.txt src/default-apps.zip.b64 node_modules
	npx shadow-cljs watch app 

watch-site: slingcode.net/index.html slingcode.net/publish.html $(SITEFILES_DEST)
	cd slingcode.net && live-server --no-browser --port=8000 --wait=500 &
	while true; do $(MAKE) -q || $(MAKE); sleep 0.5; done

repl:
	npx shadow-cljs cljs-repl app

clean:
	rm -rf build/*
	lein clean
