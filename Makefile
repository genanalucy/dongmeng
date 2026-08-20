SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

.PHONY: help dev test web-test web-typecheck web-lint web-build agent-test agent-vet agent-build prepare-official-ast officialast-test officialast-vet officialast-build officialast-check smoke-local

help:
	@printf '%s\n' 'Targets:' '  make dev                Prepare and run the real AST Agent with Vite' '  make test               Run Web, default Go, and real AST verification' '  make prepare-official-ast  Download and prepare pinned official protobuf files' '  make officialast-check  Prepare, test, vet, and build the real AST client' '  make smoke-local        Run the real local integration smoke test'

dev:
	@./scripts/dev.sh

test: web-test web-typecheck web-lint web-build agent-test agent-vet agent-build officialast-check

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

officialast-check: officialast-test officialast-vet officialast-build

smoke-local:
	@./scripts/smoke-local.sh
