STATIC=index.html css/main.css

all: build/js/main.js $(foreach S, $(STATIC), build/$(S))

build/js/main.js: $(shell find src) package.json shadow-cljs.edn
	npx shadow-cljs compile prod

build/%: public/%
	@mkdir -p `dirname $@`
	cp $< $@

.PHONY: watch clean

watch:
	npx shadow-cljs watch dev

repl:
	npx shadow-cljs cljs-repl dev

clean:
	rm -rf build/*
	lein clean
