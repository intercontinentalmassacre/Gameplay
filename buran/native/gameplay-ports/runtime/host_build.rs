use std::env;

fn main() {
    println!("cargo:rerun-if-env-changed=PORT_HOST_ID");
    let id = env::var("PORT_HOST_ID").unwrap_or_else(|_| "app.gameplay".into());
    assert!(
        id.contains('.') && id.split('.').all(|part| {
            part.as_bytes().first().is_some_and(u8::is_ascii_alphabetic)
                && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        }),
        "PORT_HOST_ID must be an Android application ID"
    );
    println!("cargo:rustc-env=PORT_HOST_ID={id}");
}
