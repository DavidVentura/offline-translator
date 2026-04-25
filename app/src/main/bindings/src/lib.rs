uniffi::setup_scaffolding!();

pub mod adblock;
pub mod bergamot;
pub mod transliterate;
pub mod uniffi_catalog;

#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
pub extern "C" fn JNI_OnLoad(_vm: *mut std::ffi::c_void, _reserved: *mut std::ffi::c_void) -> i32 {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("rust-bindings"),
    );
    std::panic::set_hook(Box::new(|info| {
        log::error!("rust panic: {info}");
    }));
    0x0001_0006
}
