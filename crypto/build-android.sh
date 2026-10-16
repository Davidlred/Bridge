#!/usr/bin/env bash
# Builds libbridge_crypto.so for Android and regenerates the Kotlin and Swift bindings (the iOS binary is built on macOS by build-ios.sh).
#   needs: rustup targets aarch64-linux-android x86_64-linux-android armv7-linux-androideabi, cargo-ndk, an Android NDK
#   usage: crypto/build-android.sh [jniLibs dir]   (Gradle calls it with app/build/rustJniLibs)
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-../android/app/build/rustJniLibs}"
cargo ndk -t arm64-v8a -t x86_64 -t armeabi-v7a -o "$OUT" build --release --lib
# Bindings come from a host build of the same crate (the interface is identical on every platform).
cargo build --lib
LIB=$(ls target/debug/ | grep -E '^(lib)?bridge_crypto\.(dll|so|dylib)$' | head -1)
cargo run --quiet --bin uniffi-bindgen generate --library "target/debug/$LIB" --language kotlin --out-dir ../android/app/src/main/java --no-format
GEN=target/swift-bindings
cargo run --quiet --bin uniffi-bindgen generate --library "target/debug/$LIB" --language swift --out-dir "$GEN" --no-format
cp "$GEN/bridge_crypto.swift" ../ios/BridgeCrypto/Sources/BridgeCrypto/
cp "$GEN/bridge_cryptoFFI.h" ../ios/BridgeCrypto/include/
cp "$GEN/bridge_cryptoFFI.modulemap" ../ios/BridgeCrypto/include/module.modulemap
