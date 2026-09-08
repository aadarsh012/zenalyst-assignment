# Housing allocation backend — developer entry points.
#
# Everything a reviewer needs to run this project is a target in this file. Nothing here
# depends on a particular editor or IDE.

SHELL := /bin/bash
.DEFAULT_GOAL := help

# --- toolchain discovery ----------------------------------------------------
# Java 17 is required (the project targets 17). Prefer an explicit JAVA_HOME, then
# macOS java_home, then the Homebrew keg.
ifeq ($(origin JAVA_HOME), undefined)
  JAVA_HOME := $(shell /usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)
endif
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

# Container runtime. Both `docker compose` and Testcontainers follow DOCKER_HOST.
#
# Docker Desktop publishes a socket the docker CLI finds on its own, so when it is present
# nothing needs setting and the default lookup is left alone. Colima puts its socket where the
# default lookup misses it, so it — and only it — needs an explicit DOCKER_HOST, plus a hint
# telling Testcontainers which socket path to mount into its reaper container.
#
# An explicit DOCKER_HOST from the caller always wins over both.
# Docker Desktop keeps its credential helper in ~/.docker/bin, which is not on PATH when the
# docker CLI came from Homebrew rather than from Desktop's own installer. Without it, config.json
# names a credsStore ("desktop") whose binary cannot be found and every image pull fails — even
# for public images that need no credentials at all.
DOCKER_DESKTOP_BIN := $(HOME)/.docker/bin
ifneq ($(wildcard $(DOCKER_DESKTOP_BIN)),)
  export PATH := $(PATH):$(DOCKER_DESKTOP_BIN)
endif

ifeq ($(origin DOCKER_HOST), undefined)
  DESKTOP_SOCK := $(HOME)/.docker/run/docker.sock
  COLIMA_SOCK  := $(HOME)/.colima/default/docker.sock
  ifeq ($(wildcard $(DESKTOP_SOCK)),)
    ifneq ($(wildcard $(COLIMA_SOCK)),)
      export DOCKER_HOST := unix://$(COLIMA_SOCK)
      export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE := /var/run/docker.sock
    endif
  endif
endif

MVN := ./mvnw
PSQL := docker compose exec -T postgres psql -U housing -d housing

# --- targets ----------------------------------------------------------------

.PHONY: help
help: ## Show this help
	@echo "Housing allocation backend"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "  JAVA_HOME   $(JAVA_HOME)"
	@echo "  DOCKER_HOST $(if $(DOCKER_HOST),$(DOCKER_HOST),<default>)"

.PHONY: up
up: ## Start PostgreSQL and wait for it to accept connections
	docker compose up -d --wait

.PHONY: down
down: ## Stop PostgreSQL (data is preserved)
	docker compose down

.PHONY: clean-db
clean-db: ## Stop PostgreSQL and DESTROY its data volume
	docker compose down -v

.PHONY: run
run: up ## Run the application against the local database
	$(MVN) spring-boot:run

.PHONY: test
test: ## Unit and architecture tests only — no database, no Docker
	$(MVN) test

.PHONY: verify
verify: ## Full build: unit, architecture and integration tests
	$(MVN) verify

.PHONY: build
build: ## Package the application jar (skipping tests)
	$(MVN) -DskipTests package

.PHONY: psql
psql: ## Open a psql shell on the local database
	docker compose exec postgres psql -U housing -d housing

.PHONY: schema
schema: ## Print the current database schema
	@$(PSQL) -c '\d+ scheme' -c '\d+ audit_event' -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;'

.PHONY: demo
demo: up ## Run a whole scheme end to end: 4,000 applicants, 600 flats, then verify it
	@echo "Start the application first (make run), then this drives it through the public API."
	python3 scripts/demo.py

.PHONY: demo-small
demo-small: up ## The same, with 400 applicants — quicker
	python3 scripts/demo.py --count 400 --flats 60 --scheme DEMO-SMALL

.PHONY: clean
clean: ## Remove build output
	$(MVN) clean

.PHONY: migrate
migrate: up ## Apply Flyway migrations without starting the application
	$(MVN) -q flyway:migrate

.PHONY: migrate-info
migrate-info: up ## Show which migrations have been applied
	$(MVN) flyway:info

.PHONY: seed
seed: migrate ## Load the local development fixture (the demo scheme)
	@$(PSQL) -v ON_ERROR_STOP=1 -f - < scripts/dev-seed.sql && echo "seeded"
