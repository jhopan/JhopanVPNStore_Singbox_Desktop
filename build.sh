#!/bin/bash
# Cross-platform build script for Jhopan VPN Desktop

set -e

VERSION="1.0.0"
OUTDIR="dist"
BINARY_NAME="jhopan-vpn"

# Create output directory
mkdir -p "$OUTDIR"

echo "Building Jhopan VPN Desktop v$VERSION..."
echo ""

# Build matrix: OS | ARCH | BINARY_SUFFIX
builds=(
  "windows:amd64:.exe"
  "windows:386:.exe"
  "windows:arm64:.exe"
  "linux:amd64:"
  "linux:386:"
  "linux:arm:"
  "linux:arm64:"
  "darwin:amd64:"
  "darwin:arm64:"
)

for build in "${builds[@]}"; do
  IFS=':' read -r os arch suffix <<< "$build"
  output="${OUTDIR}/${BINARY_NAME}-${os}-${arch}${suffix}"
  
  echo "Building for $os/$arch..."
  GOOS=$os GOARCH=$arch go build -ldflags="-s -w" \
    -o "$output" ./cmd/jhopan-vpn
  
  # Get file size
  if [ -f "$output" ]; then
    size=$(du -h "$output" | cut -f1)
    echo "  ✓ $output ($size)"
  fi
done

echo ""
echo "Build complete! Binaries in $OUTDIR/"
echo ""
echo "Platform targets:"
ls -lh "$OUTDIR"/ | tail -n +2 | awk '{print "  " $9 " (" $5 ")"}'
