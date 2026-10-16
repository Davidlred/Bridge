#!/usr/bin/env bash
# Builds libbridge_crypto.so for Android and regenerates the Kotlin bindings.
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
