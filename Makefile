.PHONY: help build build-windows build-mac build-linux clean test run

# Project variables
PROJECT_NAME := jhopan-vpn
VERSION := 0.1.0
OUT_DIR := ./out
CMD_PATH := ./cmd/jhopan-vpn

help:
	@echo "JhopanVPN Desktop - Build Commands"
	@echo ""
	@echo "Targets:"
	@echo "  build          - Build for current platform"
	@echo "  build-windows  - Build for Windows (64-bit)"
	@echo "  build-mac      - Build for macOS (Intel + Apple Silicon)"
	@echo "  build-linux    - Build for Linux (64-bit)"
	@echo "  build-all      - Build for all platforms"
	@echo "  clean          - Remove build artifacts"
	@echo "  test           - Run unit tests"
	@echo "  run            - Run the application"
	@echo ""

# Default build for current platform
build: $(OUT_DIR)
	@echo "Building for current platform..."
	go build -o $(OUT_DIR)/$(PROJECT_NAME) ./cmd/jhopan-vpn

# Windows build (requires Windows or cross-compilation tools)
build-windows: $(OUT_DIR)
	@echo "Building for Windows (amd64)..."
	GOOS=windows GOARCH=amd64 go build -o $(OUT_DIR)/$(PROJECT_NAME)-windows.exe ./cmd/jhopan-vpn

# macOS builds (Intel 64-bit and Apple Silicon)
build-mac: $(OUT_DIR)
	@echo "Building for macOS (Intel)..."
	GOOS=darwin GOARCH=amd64 go build -o $(OUT_DIR)/$(PROJECT_NAME)-macos-intel ./cmd/jhopan-vpn
	@echo "Building for macOS (Apple Silicon)..."
	GOOS=darwin GOARCH=arm64 go build -o $(OUT_DIR)/$(PROJECT_NAME)-macos-arm64 ./cmd/jhopan-vpn

# Linux build
build-linux: $(OUT_DIR)
	@echo "Building for Linux (amd64)..."
	GOOS=linux GOARCH=amd64 go build -o $(OUT_DIR)/$(PROJECT_NAME)-linux ./cmd/jhopan-vpn

# Build for all platforms
build-all: build-windows build-mac build-linux
	@echo "All platforms built successfully!"
	@echo "Binaries are in $(OUT_DIR)/"

# Create output directory
$(OUT_DIR):
	mkdir -p $(OUT_DIR)

# Clean build artifacts
clean:
	rm -rf $(OUT_DIR)
	go clean -cache

# Run tests
test:
	@echo "Running tests..."
	go test -v ./...

# Run the application
run:
	@echo "Running JhopanVPN..."
	go run ./cmd/jhopan-vpn

# Install dependencies
deps:
	@echo "Installing dependencies..."
	go mod download
	go mod tidy

# Development build with debugging info
dev:
	@echo "Building development version (with debug info)..."
	go build -v -o $(OUT_DIR)/$(PROJECT_NAME)-dev ./cmd/jhopan-vpn

# Install the built application (Unix-like systems)
install: build
	@echo "Installing JhopanVPN..."
	mkdir -p $(HOME)/bin
	cp $(OUT_DIR)/$(PROJECT_NAME) $(HOME)/bin/
	@echo "Installed to $(HOME)/bin/$(PROJECT_NAME)"
