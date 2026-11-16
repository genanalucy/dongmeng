SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

.PHONY: check android-check agent-check cloud-check contract-check admin-web-check web-check download-check navigation-check tooling-check db-check help dev test web-test web-typecheck web-lint web-build agent-test agent-vet agent-build prepare-official-ast officialast-test officialast-vet officialast-build officialast-check smoke-local

help:
	@printf '%s\n' 'Targets:' '  make dev                Prepare and run the real AST Agent with Vite' '  make test               Run all automatic groups; print optional DB/device skips' '  bash scripts/check.sh --help  Canonical entry (make is optional)' '  See docs/testing/README.md for setup, scope, and acceptance' '  make prepare-official-ast  Download and prepare pinned official protobuf files' '  make officialast-check  Test/vet/build officialast after explicit dependency preparation' '  make smoke-local        Run the real local integration smoke test'

dev:
	@./scripts/dev.sh

# Canonical entry also works on hosts without make. See docs/testing/README.md.
test check:
	@bash scripts/check.sh all

android-check agent-check cloud-check contract-check admin-web-check web-check download-check navigation-check tooling-check db-check:
	@bash scripts/check.sh $(@:-check=)

web-test:
	@npm --prefix web run test

web-typecheck:
	@npm --prefix web run typecheck

web-lint:
	@npm --prefix web run lint

web-build:
	@npm --prefix web run build

agent-test:
	@cd agent && go test ./...

agent-vet:
	@cd agent && go vet ./...

agent-build:
	@binary="$$(mktemp "$${TMPDIR:-/tmp}/translator-agent-check.XXXXXX")"; \
	trap 'rm -f "$$binary"' EXIT HUP INT TERM; \
	cd agent && go build -o "$$binary" ./cmd/translator-agent

prepare-official-ast:
	@./scripts/prepare-official-ast.sh

officialast-test: prepare-official-ast
	@cd agent && go test -tags officialast ./...

officialast-vet: prepare-official-ast
	@cd agent && go vet -tags officialast ./...

officialast-build: prepare-official-ast
	@binary="$$(mktemp "$${TMPDIR:-/tmp}/translator-agent-official-check.XXXXXX")"; \
	trap 'rm -f "$$binary"' EXIT HUP INT TERM; \
	cd agent && go build -tags officialast -o "$$binary" ./cmd/translator-agent

officialast-check:
	@bash scripts/check.sh officialast

smoke-local:
	@./scripts/smoke-local.sh
