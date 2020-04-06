STATIC=index.html style.css logo.png
BUILD=build/js/main.js $(foreach S, $(STATIC), build/$(S))

# bootleg stuff
BOOTLEGVERSION=0.1.7
BOOTLEG=./bin/bootleg-$(BOOTLEGVERSION)

slingcode.html: $(BOOTLEG) $(BUILD) build/logo-b64-href.txt
	$(BOOTLEG) src/slingcode-bootleg.clj > $@

build/logo-b64-href.txt: build/logo.png
	echo "data:image/png;base64,"`base64 $< -w0` > $@

build/js/main.js: $(shell find src) package.json shadow-cljs.edn
	npx shadow-cljs release app

build/style.css: public/*.css
	cat public/codemirror.css public/erlang-dark.css public/style.css > $@

build/%: public/%
	@mkdir -p `dirname $@`
	cp $< $@

BOOTLEGTAR=bootleg-$(BOOTLEGVERSION)-linux-amd64.tgz
BOOTLEGURL=https://github.com/retrogradeorbit/bootleg/releases/download/v${BOOTLEGVERSION}/${BOOTLEGTAR}

$(BOOTLEG):
	@echo "Installing bootleg."
	mkdir -p bin
	wget $(BOOTLEGURL) -O $(BOOTLEGTAR)
	tar -zxvf $(BOOTLEGTAR) && mv bootleg $(BOOTLEG)
	rm $(BOOTLEGTAR)

# dev targets

.PHONY: watch clean

watch:
	npx shadow-cljs watch app 

repl:
	npx shadow-cljs cljs-repl app

clean:
	rm -rf build/*
	lein clean
