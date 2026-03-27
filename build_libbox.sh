#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  Build libbox.aar from sing-box source
#  Requires: Go 1.22+, Android NDK (via ANDROID_HOME or ANDROID_NDK_HOME)
#
#  Usage (Linux/Mac/WSL):
#    chmod +x build_libbox.sh
#    ./build_libbox.sh
#
#  Or via Docker:
#    docker run --rm -v $(pwd):/workspace -w /workspace golang:1.22 bash build_libbox.sh
# ═══════════════════════════════════════════════════════════════

set -e

SINGBOX_VERSION="v1.11.0"
OUTPUT_DIR="app/libs"
TARGETS="android/arm64,android/arm"
JAVA_PKG="libbox"
BUILD_TAGS="with_quic,with_clash_api,with_utls,with_gvisor"

echo "═══════════════════════════════════════════"
echo "  Building libbox.aar from sing-box ${SINGBOX_VERSION}"
echo "═══════════════════════════════════════════"

# Check Go
if ! command -v go &> /dev/null; then
    echo "✗ Go is not installed. Install Go 1.22+ first."
    exit 1
fi
echo "✓ Go: $(go version)"

# Install gomobile
echo "⬇ Installing gomobile..."
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
export PATH="$PATH:$(go env GOPATH)/bin"

echo "⬇ Initializing gomobile..."
gomobile init

# Clone sing-box
WORK_DIR=$(mktemp -d)
echo "⬇ Cloning sing-box ${SINGBOX_VERSION} to ${WORK_DIR}..."
git clone --depth 1 --branch "${SINGBOX_VERSION}" https://github.com/SagerNet/sing-box.git "${WORK_DIR}/sing-box"

cd "${WORK_DIR}/sing-box"

# Build libbox.aar
echo "🔨 Building libbox.aar (targets: ${TARGETS})..."
echo "   Tags: ${BUILD_TAGS}"
echo "   This may take 5-15 minutes..."

gomobile bind \
    -target="${TARGETS}" \
    -javapkg="${JAVA_PKG}" \
    -tags="${BUILD_TAGS}" \
    -trimpath \
    -ldflags="-s -w" \
    ./experimental/libbox

# Copy to project
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "${SCRIPT_DIR}/${OUTPUT_DIR}"
cp libbox.aar "${SCRIPT_DIR}/${OUTPUT_DIR}/libbox.aar"
cp libbox-sources.jar "${SCRIPT_DIR}/${OUTPUT_DIR}/libbox-sources.jar" 2>/dev/null || true

# Cleanup
rm -rf "${WORK_DIR}"

echo ""
echo "═══════════════════════════════════════════"
echo "  ✓ libbox.aar built successfully!"
echo "  → ${OUTPUT_DIR}/libbox.aar"
echo "  Size: $(du -h "${SCRIPT_DIR}/${OUTPUT_DIR}/libbox.aar" | cut -f1)"
echo "═══════════════════════════════════════════"
