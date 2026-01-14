GRADLE ?= ./gradlew --no-daemon

.DEFAULT_GOAL := check

.PHONY: check test lint callstacks report clean

## everything CI runs: the test suite and CodeNarc over the library and the tests
check:
	$(GRADLE) check

## the test suite only
test:
	$(GRADLE) test

## CodeNarc
lint:
	$(GRADLE) codenarcMain codenarcTest

## rewrite the recorded call stacks from the current behaviour; review the diff
callstacks:
	$(GRADLE) updateCallstacks

## open the HTML reports
report:
	@echo "tests:    build/reports/tests/test/index.html"
	@echo "codenarc: build/reports/codenarc/main.html"

clean:
	$(GRADLE) clean
