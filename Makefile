# rke2-adoption-controller dev tasks. Run inside the repo's flox env (`flox activate`),
# which provides go, controller-gen, kubectl and make (see .flox/env/manifest.toml) — so
# the tools are on PATH and there is nothing to download.

# controller-gen ships with the flox env; override only to pin a different binary.
CONTROLLER_GEN ?= controller-gen

.PHONY: all
all: generate manifests fmt vet build ## Regenerate, format, vet and build.

.PHONY: generate
generate: ## Regenerate DeepCopy methods (api/v1alpha1/zz_generated.deepcopy.go).
	$(CONTROLLER_GEN) object paths=./api/...

.PHONY: manifests
manifests: ## Regenerate CRDs into config/crd (the single source rke2lab stages).
	$(CONTROLLER_GEN) crd paths=./api/... output:crd:dir=config/crd

.PHONY: fmt
fmt: ## Format Go sources.
	go fmt ./...

.PHONY: vet
vet: ## Vet Go sources.
	go vet ./...

.PHONY: build
build: ## Build all packages.
	go build ./...

.PHONY: test
test: ## Run unit tests.
	go test ./...

.PHONY: help
help: ## Show this help.
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'
