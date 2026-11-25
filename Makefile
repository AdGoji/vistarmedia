nrepl:
	@clojure -M:test:nREPL
.PHONY: nrepl

clj-kondo-populate-cache:
	@clj-kondo --copy-configs --dependencies --lint "$$(clojure -Spath -A:test)"
.PHONY: clj-kondo-populate-cache

lint: clj-kondo-populate-cache
	@clj-kondo --lint src test
.PHONY: lint

test:
	@clojure -M:test:test-runner
.PHONY: test

format-check:
	@cljfmt check
.PHONY: format-check

format-fix:
	@cljfmt fix
.PHONY: format-fix

clean:
	@clojure -T:build clean
.PHONY: clean

jar:
	@clojure -T:build jar
.PHONY: jar

publish:
	@clojure -T:build deploy
.PHONY: publish
