// Generates the Kotlin and Swift bindings: `cargo run --bin uniffi-bindgen generate --library <lib> --language kotlin|swift --out-dir <dir>`.
fn main() {
    uniffi::uniffi_bindgen_main()
}
