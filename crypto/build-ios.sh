#!/usr/bin/env bash
# Builds ios/BridgeCrypto/bridge_cryptoFFI.xcframework (macOS only): the Rust library for devices and the simulator.
# The Swift bindings and C header are generated on any machine (see build-android.sh) and committed.
set -euo pipefail
cd "$(dirname "$0")"
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
for t in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do cargo build --release --lib --target "$t"; done
mkdir -p target/ios-sim
# One simulator library for Apple-silicon and Intel simulators
lipo -create target/aarch64-apple-ios-sim/release/libbridge_crypto.a target/x86_64-apple-ios/release/libbridge_crypto.a -output target/ios-sim/libbridge_crypto.a
OUT=../ios/BridgeCrypto
rm -rf "$OUT/bridge_cryptoFFI.xcframework"
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libbridge_crypto.a -headers "$OUT/include" \
  -library target/ios-sim/libbridge_crypto.a -headers "$OUT/include" \
  -output "$OUT/bridge_cryptoFFI.xcframework"
