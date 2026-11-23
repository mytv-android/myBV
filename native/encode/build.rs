fn main() {
    println!("cargo:rerun-if-changed=src/ffmpeg_glue.c");
    println!("cargo:rerun-if-env-changed=FFMPEG_PREFIX");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    let prefix = std::path::PathBuf::from(
        std::env::var_os("FFMPEG_PREFIX")
            .expect("FFMPEG_PREFIX must point to an Android FFmpeg audio build"),
    );
    cc::Build::new()
        .file("src/ffmpeg_glue.c")
        .include(prefix.join("include"))
        .flag_if_supported("-std=c11")
        .compile("mybv_ffmpeg_glue");
    println!(
        "cargo:rustc-link-search=native={}",
        prefix.join("lib").display()
    );
    for library in ["avcodec_mybv", "swresample_mybv", "avutil_mybv"] {
        println!("cargo:rustc-link-lib=dylib={library}");
    }
}
