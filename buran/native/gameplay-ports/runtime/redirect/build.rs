use std::{env, path::PathBuf, process::Command};

mod host {
    include!("../host_build.rs");
    pub fn configure() { main(); }
}

fn main() {
    host::configure();
    println!("cargo:rerun-if-changed=../host_build.rs");
    println!("cargo:rerun-if-changed=src/asprintf.c");
    println!("cargo:rerun-if-env-changed=RUSTC_LINKER");
    let compiler = env::var_os("RUSTC_LINKER").unwrap_or_else(|| "cc".into());
    let object = PathBuf::from(env::var_os("OUT_DIR").unwrap()).join("asprintf.o");
    let status = Command::new(compiler)
        .args(["-fPIC", "-O2", "-c", "src/asprintf.c", "-o"])
        .arg(&object)
        .status()
        .expect("compile the variadic ABI adapter");
    assert!(status.success(), "failed to compile the variadic ABI adapter");
    println!("cargo:rustc-link-arg={}", object.display());
}
