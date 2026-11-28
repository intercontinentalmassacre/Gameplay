//! Rust port of `libsteambootstrap.so`.
//!
//! The original is an Android/AArch64 PIE executable (despite the `.so` name).
//! It boots the Steam client for the host application, patches libsteamclient so it
//! reports Windows, logs in with cached credentials, prepares the app, and
//! serves the overlay IPC over an abstract Unix socket.
//!
//! Build:
//!     cargo build --release -p steambootstrap
//!
//! Run:
//!     libsteambootstrap <appid> <libsteamclient.so> [readyfile]
//!
//! The vtable offsets and build signatures are the ones observed in the
//! original binary.  This port is intentionally close to the decompiled C.

#![allow(clippy::missing_safety_doc)]

use libc::{c_char, c_int, c_void};
use std::ffi::{CStr, CString};
use std::io::{BufRead, BufReader, Write};
#[cfg(target_os = "android")]
use std::os::android::net::SocketAddrExt;
#[cfg(target_os = "linux")]
use std::os::linux::net::SocketAddrExt;
use std::os::unix::net::{SocketAddr, UnixListener, UnixStream};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicPtr, AtomicU32, AtomicU64, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

const SESSION_ROOT: &str = concat!("/data/data/", env!("PORT_HOST_ID"), "/files/imagefs/.steambootstrap");
const CERT_PATH: &str = concat!("/data/data/", env!("PORT_HOST_ID"), "/files/cacert.pem");

const DEFAULT_OVERLAY: &str = concat!(env!("PORT_HOST_ID"), "-steam-overlay");

static STEAMCLIENT: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());

static PIPE: AtomicI32 = AtomicI32::new(0);
static USER: AtomicI32 = AtomicI32::new(0);
static APPID: AtomicU32 = AtomicU32::new(0);
static STEAMID64: AtomicU64 = AtomicU64::new(0);
static LOBBY: AtomicU64 = AtomicU64::new(0);

static OVERLAY_RUNNING: AtomicBool = AtomicBool::new(false);
static CALLBACK_RUNNING: AtomicBool = AtomicBool::new(false);
static STOPPING: AtomicBool = AtomicBool::new(false);
static DIALOG_PENDING: AtomicBool = AtomicBool::new(false);
static PICS_PRIMED: AtomicBool = AtomicBool::new(false);

static DIALOG: Mutex<String> = Mutex::new(String::new());

/* ------------------------------------------------------------------ */
/* Logging                                                            */
/* ------------------------------------------------------------------ */

macro_rules! log {
    ($prio:expr, $tag:expr, $($arg:tt)*) => {
        eprintln!("{}: {}", $tag, format!($($arg)*))
    };
}

/* ------------------------------------------------------------------ */
/* FFI helpers                                                        */
/* ------------------------------------------------------------------ */

type CreateInterfaceFn = unsafe extern "C" fn(*const c_char, *mut c_int) -> *mut c_void;
type GetInterfaceFn = unsafe extern "C" fn(*mut c_void, c_int, c_int) -> *mut c_void;
type BoolFn = unsafe extern "C" fn(*mut c_void) -> c_int;
type BoolUserFn = unsafe extern "C" fn(*mut c_void, u64) -> c_int;
type FriendGameFn = unsafe extern "C" fn(*mut c_void, u64, *mut c_void) -> c_int;
type IntIntFn = unsafe extern "C" fn(*mut c_void, c_int) -> c_int;
type U64IntIntFn = unsafe extern "C" fn(*mut c_void, c_int, c_int) -> u64;
type StringU64Fn = unsafe extern "C" fn(*mut c_void, u64) -> *const c_char;
type StringU64StringFn = unsafe extern "C" fn(*mut c_void, u64, *const c_char) -> *const c_char;
type LogonFn = unsafe extern "C" fn(*mut c_void, u64) -> c_int;
type HasCachedFn = unsafe extern "C" fn(*mut c_void, *const c_char) -> c_int;
type SelectCachedAccountFn = unsafe extern "C" fn(*mut c_void, *const c_char, c_int) -> c_int;
type SetLangFn = unsafe extern "C" fn(*mut c_void, *const c_char) -> c_int;
type SetAccountFn = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char, c_int) -> c_int;
type SetRefreshTokenFn = unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char);
type OwnershipFn = unsafe extern "C" fn(*mut c_void, u32, c_int) -> c_int;
type RequestInfoFn = unsafe extern "C" fn(*mut c_void, *mut u32, c_int) -> c_int;
type NotifyLobbyFn = unsafe extern "C" fn(*mut c_void, u32, u64, u64) -> c_int;
type NotifyRpFn = unsafe extern "C" fn(*mut c_void, u32, u64, *const c_char) -> c_int;
type InviteFn = unsafe extern "C" fn(*mut c_void, u32, u64, *const c_char) -> c_int;
type GetSubFn = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type FindInterfaceFn = unsafe extern "C" fn(*mut c_void, *const c_char) -> *mut c_void;
type GetNameFn = unsafe extern "C" fn(*mut c_void, *const c_char) -> *const c_char;
type GetCallbackFn = unsafe extern "C" fn(c_int, *mut SteamCallback) -> c_int;
type FreeCallbackFn = unsafe extern "C" fn(c_int);

unsafe fn callback_exports(handle: *mut c_void) -> Option<(GetCallbackFn, FreeCallbackFn)> {
    let get = sym(handle, "Steam_BGetCallback");
    let free = sym(handle, "Steam_FreeLastCallback");
    if get.is_null() || free.is_null() {
        return None;
    }
    Some((
        std::mem::transmute::<*mut c_void, GetCallbackFn>(get),
        std::mem::transmute::<*mut c_void, FreeCallbackFn>(free),
    ))
}

#[repr(C)]
struct SteamCallback {
    size: u32,
    id: u32,
    data: *mut c_void,
    data_size: u32,
}

fn decode_dialog(data: &[u8]) -> Option<(String, u64)> {
    let lobby = u64::from_ne_bytes(data.get(0x2000..0x2008)?.try_into().ok()?);
    // The original copies at most 63 bytes plus the terminator.
    let dialog = &data[..63];
    let end = dialog.iter().position(|&b| b == 0).unwrap_or(dialog.len());
    Some((String::from_utf8_lossy(&dialog[..end]).into_owned(), lobby))
}

#[cfg(test)]
#[test]
fn dialog_uses_full_lobby_id_and_bounds_text() {
    let mut data = [b'x'; 0x2008];
    let lobby = 0xfedc_ba98_7654_3200u64;
    data[0x2000..].copy_from_slice(&lobby.to_ne_bytes());
    assert_eq!(decode_dialog(&data), Some(("x".repeat(63), lobby)));
    data[3] = 0;
    assert_eq!(decode_dialog(&data), Some(("xxx".into(), lobby)));
    assert!(decode_dialog(&data[..0x2007]).is_none());
}

#[repr(C)]
struct FriendGameInfo {
    appid: u32,
    pad: u32,
    lobby: u64,
}

unsafe fn sym(handle: *mut c_void, name: &str) -> *mut c_void {
    let Ok(cname) = CString::new(name) else {
        return std::ptr::null_mut();
    };
    libc::dlsym(handle, cname.as_ptr())
}

unsafe fn dlopen(path: &str) -> *mut c_void {
    let Ok(cpath) = CString::new(path) else {
        return std::ptr::null_mut();
    };
    libc::dlopen(cpath.as_ptr(), libc::RTLD_NOW)
}

unsafe fn vfn<T: Copy>(object: *mut c_void, offset: usize) -> Option<T> {
    if object.is_null() {
        return None;
    }
    let vtable = *(object as *mut *mut *mut c_void);
    if vtable.is_null() {
        return None;
    }
    let slot = *vtable.add(offset / std::mem::size_of::<*mut c_void>());
    if slot.is_null() {
        return None;
    }
    Some(std::mem::transmute_copy(&slot))
}

macro_rules! vcall {
    ($object:expr, $offset:expr, $ty:ty, $($arg:expr),* $(,)?) => {{
        match unsafe { vfn::<$ty>($object, $offset) } {
            Some(f) => Some(unsafe { f($($arg),*) }),
            None => None,
        }
    }};
}

unsafe fn cstr_opt(ptr: *const c_char) -> Option<String> {
    if ptr.is_null() {
        None
    } else {
        Some(CStr::from_ptr(ptr).to_string_lossy().into_owned())
    }
}

fn write_text(path: &str, text: &str) -> std::io::Result<()> {
    std::fs::write(path, text)
}

fn move_file(from: &Path, to: &Path) -> std::io::Result<()> {
    if std::fs::rename(from, to).is_ok() {
        return Ok(());
    }
    std::fs::copy(from, to)?;
    std::fs::remove_file(from)
}

/* ------------------------------------------------------------------ */
/* libsteamclient build profiles                                      */
/* ------------------------------------------------------------------ */

struct BuildProfile {
    create_interface_delta: usize,
    engine_global_offset: usize,
    get_subinterface_offset: usize,
    find_interface_offset: usize,
    get_name_offset: usize,
    get_os_type_offset: usize,
}

const PROFILES: [BuildProfile; 3] = [
    BuildProfile {
        create_interface_delta: 0x1a1ffbc,
        engine_global_offset: 0x022c60c8,
        get_subinterface_offset: 0x00ce1350,
        find_interface_offset: 0x01a23738,
        get_name_offset: 0x01a254f8,
        get_os_type_offset: 0x01bd3ba4,
    },
    BuildProfile {
        create_interface_delta: 0x1a686dc,
        engine_global_offset: 0x02313c98,
        get_subinterface_offset: 0x00d0f9d8,
        find_interface_offset: 0x01a6be58,
        get_name_offset: 0x01a6dc18,
        get_os_type_offset: 0x01c1a170,
    },
    BuildProfile {
        create_interface_delta: 0x1a75648,
        engine_global_offset: 0x02321a18,
        get_subinterface_offset: 0x00d13d9c,
        find_interface_offset: 0x01a78f24,
        get_name_offset: 0x01a7ac80,
        get_os_type_offset: 0x01c27070,
    },
];

struct DlInfoResult {
    base: usize,
}

fn find_profile(create_interface: *mut c_void) -> Option<(DlInfoResult, &'static BuildProfile)> {
    let mut info: libc::Dl_info = unsafe { std::mem::zeroed() };
    if unsafe { libc::dladdr(create_interface, &mut info) } == 0 || info.dli_fbase.is_null() {
        return None;
    }
    let base = info.dli_fbase as usize;
    let delta = create_interface as usize - base;
    let profile = PROFILES
        .iter()
        .find(|p| p.create_interface_delta == delta)?;
    Some((DlInfoResult { base }, profile))
}

/* ------------------------------------------------------------------ */
/* Patching                                                           */
/* ------------------------------------------------------------------ */

#[cfg(target_arch = "aarch64")]
unsafe fn flush_icache(addr: usize) {
    use std::arch::asm;
    asm!(
        "dc cvau, {a}",
        "dsb ish",
        "ic ivau, {a}",
        "dsb ish",
        "isb",
        a = in(reg) addr,
        options(nostack, preserves_flags)
    );
}

#[cfg(not(target_arch = "aarch64"))]
unsafe fn flush_icache(_addr: usize) {}

unsafe fn patch_os_type() {
    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let create_interface = sym(handle, "CreateInterface");
    if create_interface.is_null() {
        return;
    }
    let Some((info, profile)) = find_profile(create_interface) else {
        log!(
            5,
            "SteamBootstrap",
            "no GetOSType offset for this build (CreateInterface @ {:#x}); \
             OS identity stays android (MP joins may fail)",
            create_interface as usize
        );
        return;
    };

    let code = (info.base + profile.get_os_type_offset) as *mut u32;
    if std::ptr::read_volatile(code) == 0x52800200
        && std::ptr::read_volatile(code.add(1)) == 0xd65f03c0
    {
        log!(
            4,
            "SteamBootstrap",
            "patch_os_type_to_windows: GetOSType already patched"
        );
        return;
    }

    let mut page_size = libc::sysconf(libc::_SC_PAGESIZE);
    if page_size <= 0 {
        page_size = 0x4000;
    }
    let page = (code as usize) & !(page_size as usize - 1);

    if libc::mprotect(
        page as *mut c_void,
        page_size as usize * 2,
        libc::PROT_READ | libc::PROT_WRITE | libc::PROT_EXEC,
    ) != 0
        && libc::mprotect(
            page as *mut c_void,
            page_size as usize * 2,
            libc::PROT_READ | libc::PROT_WRITE,
        ) != 0
    {
        log!(
            5,
            "SteamBootstrap",
            "patch_os_type_to_windows: mprotect failed (errno={}), \
             OS identity left as android",
            std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
        );
        return;
    }

    std::ptr::write_volatile(code, 0x52800200); // mov w0, #0x10
    std::ptr::write_volatile(code.add(1), 0xd65f03c0); // ret
    flush_icache(code as usize);

    if libc::mprotect(
        page as *mut c_void,
        page_size as usize * 2,
        libc::PROT_READ | libc::PROT_EXEC,
    ) != 0
    {
        log!(
            5,
            "SteamBootstrap",
            "patch_os_type_to_windows: re-protect R-X failed (errno={})",
            std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
        );
    }

    log!(
        4,
        "SteamBootstrap",
        "patch_os_type_to_windows: patched GetOSType -> k_eWindows10 \
         (OS identity android->windows)"
    );
}

unsafe fn patch_oslist() {
    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let create_interface = sym(handle, "CreateInterface");
    if create_interface.is_null() {
        return;
    }
    let Some((info, profile)) = find_profile(create_interface) else {
        return;
    };

    let engine = *(info.base.wrapping_add(profile.engine_global_offset) as *mut *mut c_void);
    if engine.is_null() {
        return;
    }

    let get_sub: GetSubFn = std::mem::transmute(info.base + profile.get_subinterface_offset);
    let find_interface: FindInterfaceFn =
        std::mem::transmute(info.base + profile.find_interface_offset);
    let get_name: GetNameFn = std::mem::transmute(info.base + profile.get_name_offset);

    let sub = get_sub((engine as *mut u8).add(0x1098) as *mut c_void);
    let name_c = CString::new("oslist").unwrap();
    let oslist = find_interface(sub, name_c.as_ptr());
    if oslist.is_null() {
        return;
    }

    let empty = CString::new("").unwrap();
    let name = cstr_opt(get_name(oslist, empty.as_ptr()));
    if name.as_deref() == Some("android") {
        let p = get_name(oslist, empty.as_ptr()) as *mut u8;
        std::ptr::copy_nonoverlapping(b"windows\0".as_ptr(), p, 8);
        log!(
            4,
            "SteamBootstrap",
            "steam_prepare_app: patched engine oslist android->windows"
        );
    }
}

/* ------------------------------------------------------------------ */
/* Sibling libraries and steamservice                                 */
/* ------------------------------------------------------------------ */

fn preload_siblings(steamclient_path: &str) {
    let Some(parent) = Path::new(steamclient_path).parent() else {
        log!(
            5,
            "SteamBootstrap",
            "preload_steam_siblings: no directory in path, skipping"
        );
        return;
    };

    let mut loaded = 0usize;
    for name in [
        "libtier0_s.so",
        "libvstdlib_s.so",
        "libsteamnetworkingsockets.so",
    ] {
        let path = parent.join(name);
        let handle = unsafe { dlopen(&path.to_string_lossy()) };
        if handle.is_null() {
            log!(
                5,
                "SteamBootstrap",
                "preload_steam_siblings: failed to load {}: {}",
                name,
                unsafe { cstr_opt(libc::dlerror()).unwrap_or_default() }
            );
            continue;
        }
        loaded += 1;
        log!(
            4,
            "SteamBootstrap",
            "preload_steam_siblings: loaded {}",
            name
        );
    }

    log!(
        4,
        "SteamBootstrap",
        "preload_steam_siblings: loaded {} sibling libraries",
        loaded
    );
}

unsafe fn start_steamservice(steamclient_path: &str) {
    let Some(parent) = Path::new(steamclient_path).parent() else {
        return;
    };
    let service_path = parent.join("steamservice.so");
    let handle = dlopen(&service_path.to_string_lossy());
    if handle.is_null() {
        log!(
            5,
            "SteamBootstrap",
            "start_steamservice: failed to load steamservice.so"
        );
        return;
    }

    let start_thread = sym(handle, "SteamService_StartThread");
    if start_thread.is_null() {
        log!(5, "SteamBootstrap", "SteamService_StartThread unavailable");
        return;
    }
    let start: unsafe extern "C" fn(*const c_char) -> c_int = std::mem::transmute(start_thread);
    let name = CString::new("SteamClientService").unwrap();
    let service_result = start(name.as_ptr());
    if service_result == 0 {
        log!(
            5,
            "SteamBootstrap",
            "start_steamservice: SteamService_StartThread failed"
        );
        return;
    }

    log!(
        4,
        "SteamBootstrap",
        "start_steamservice: service thread started"
    );
}

/* ------------------------------------------------------------------ */
/* Session persistence                                                */
/* ------------------------------------------------------------------ */

fn session_paths() -> (String, String, String, String, String) {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
    let steamid = STEAMID64.load(Ordering::Relaxed);
    let sb_dir = format!("{SESSION_ROOT}/{steamid}");
    let sb_config = format!("{sb_dir}/config.vdf");
    let sb_local = format!("{sb_dir}/local.vdf");
    let home_config = format!("{home}/Steam/config/config.vdf");
    let home_local = format!("{home}/Steam/local.vdf");
    (sb_dir, sb_config, sb_local, home_config, home_local)
}

fn looks_regular(path: &str) -> bool {
    std::fs::symlink_metadata(path)
        .map(|m| m.file_type().is_file())
        .unwrap_or(false)
}

fn looks_symlink(path: &str) -> bool {
    std::fs::symlink_metadata(path)
        .map(|m| m.file_type().is_symlink())
        .unwrap_or(false)
}

fn restore_session() {
    if STEAMID64.load(Ordering::Relaxed) == 0 {
        return;
    }
    let (sb_dir, sb_config, sb_local, home_config, home_local) = session_paths();
    if !looks_regular(&sb_config) || !looks_regular(&sb_local) {
        log!(
            4,
            "SteamBootstrap",
            "No saved session files found in {}, will do fresh login",
            sb_dir
        );
        return;
    }

    log!(
        4,
        "SteamBootstrap",
        "Found saved session files in {}, restoring...",
        sb_dir
    );
    if let Some(parent) = Path::new(&home_config).parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let _ = std::fs::remove_file(&home_config);
    let _ = std::fs::remove_file(&home_local);

    match std::os::unix::fs::symlink(&sb_config, &home_config) {
        Ok(()) => log!(
            4,
            "SteamBootstrap",
            "Linked {} -> {}",
            home_config,
            sb_config
        ),
        Err(e) => log!(5, "SteamBootstrap", "Failed to symlink config.vdf: {}", e),
    }
    match std::os::unix::fs::symlink(&sb_local, &home_local) {
        Ok(()) => log!(4, "SteamBootstrap", "Linked {} -> {}", home_local, sb_local),
        Err(e) => log!(5, "SteamBootstrap", "Failed to symlink local.vdf: {}", e),
    }
}

fn save_session() {
    if STEAMID64.load(Ordering::Relaxed) == 0 {
        return;
    }
    let (sb_dir, sb_config, sb_local, home_config, home_local) = session_paths();

    let have_config = looks_regular(&home_config);
    let have_local = looks_regular(&home_local);

    log!(
        4,
        "SteamBootstrap",
        "Session file check: config.vdf exists={}, regular={}, local.vdf exists={}, regular={}",
        have_config,
        looks_regular(&home_config),
        have_local,
        looks_regular(&home_local)
    );

    if !have_config && !have_local {
        log!(
            4,
            "SteamBootstrap",
            "No session files found, nothing to save"
        );
        return;
    }
    if looks_symlink(&home_config) || looks_symlink(&home_local) {
        log!(
            4,
            "SteamBootstrap",
            "Session files are symlinks, not saving (already in .sb)"
        );
        return;
    }

    log!(4, "SteamBootstrap", "Saving session files to {}", sb_dir);
    if let Err(e) = std::fs::create_dir_all(&sb_dir) {
        log!(5, "SteamBootstrap", "Failed to create .sb directory: {}", e);
        return;
    }

    if have_config {
        match move_file(Path::new(&home_config), Path::new(&sb_config)) {
            Ok(()) => log!(4, "SteamBootstrap", "Saved config.vdf to {}", sb_config),
            Err(e) => log!(5, "SteamBootstrap", "Failed to save config.vdf: {}", e),
        }
    }
    if have_local {
        match move_file(Path::new(&home_local), Path::new(&sb_local)) {
            Ok(()) => log!(4, "SteamBootstrap", "Saved local.vdf to {}", sb_local),
            Err(e) => log!(5, "SteamBootstrap", "Failed to save local.vdf: {}", e),
        }
    }
}

/* ------------------------------------------------------------------ */
/* Login and app preparation                                          */
/* ------------------------------------------------------------------ */

unsafe fn drain_callbacks(limit: usize) -> usize {
    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let Some((get_cb, free_cb)) = callback_exports(handle) else {
        return 0;
    };
    let mut count = 0;
    while count < limit {
        let mut cb: SteamCallback = std::mem::zeroed();
        if get_cb(PIPE.load(Ordering::Relaxed), &mut cb) == 0 {
            break;
        }
        free_cb(PIPE.load(Ordering::Relaxed));
        count += 1;
    }
    count
}

unsafe fn wait_login_result(timeout: Duration) -> Option<c_int> {
    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let (get_cb, free_cb) = callback_exports(handle)?;
    let deadline = Instant::now() + timeout;
    while !STOPPING.load(Ordering::Relaxed) && Instant::now() < deadline {
        let mut cb: SteamCallback = std::mem::zeroed();
        if get_cb(PIPE.load(Ordering::Relaxed), &mut cb) == 0 {
            thread::sleep(Duration::from_millis(20));
            continue;
        }
        let id = cb.id;
        let result = if id == 0x65 {
            Some(1)
        } else if id == 0x66 {
            let value = if cb.data_size >= 4 && !cb.data.is_null() {
                *(cb.data as *mut c_int)
            } else {
                0
            };
            log!(4, "SteamBootstrap", "Login failed with EResult={}", value);
            Some(value)
        } else {
            None
        };
        free_cb(PIPE.load(Ordering::Relaxed));
        if result.is_some() {
            return result;
        }
    }
    None
}

unsafe fn login_with_token(
    user: *mut c_void,
    account: &str,
    refresh_token: &str,
    steamid: u64,
) {
    let account_c = CString::new(account).unwrap();
    let empty = CString::new("").unwrap();

    let token_c = CString::new(refresh_token).unwrap();
    let _ = vcall!(
        user,
        0x1b0,
        SetAccountFn,
        user,
        account_c.as_ptr(),
        empty.as_ptr(),
        1
    );
    let _ = vcall!(
        user,
        0x1c0,
        SetRefreshTokenFn,
        user,
        token_c.as_ptr(),
        account_c.as_ptr()
    );
    let _ = vcall!(user, 0x8, LogonFn, user, steamid);
}

unsafe fn logged_on(user: *mut c_void) -> bool {
    !user.is_null() && vcall!(user, 0x20, BoolFn, user).unwrap_or(0) != 0
}

unsafe fn wait_for_pics() {
    if PICS_PRIMED.load(Ordering::Relaxed) {
        log!(
            4,
            "SteamBootstrap",
            "steam_prepare_app: PICS already primed, skipping wait"
        );
        return;
    }

    log!(
        4,
        "SteamBootstrap",
        "steam_prepare_app: waiting for AppInfoUpdateComplete_t..."
    );
    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let Some((get_cb, free_cb)) = callback_exports(handle) else {
        PICS_PRIMED.store(true, Ordering::Relaxed);
        return;
    };

    loop {
        let mut cb: SteamCallback = std::mem::zeroed();
        while get_cb(PIPE.load(Ordering::Relaxed), &mut cb) == 0 {
            thread::sleep(Duration::from_millis(20));
        }
        let id = cb.id;
        free_cb(PIPE.load(Ordering::Relaxed));
        if id == 0x1360f3 {
            break;
        }
    }
    PICS_PRIMED.store(true, Ordering::Relaxed);
    log!(4, "SteamBootstrap", "steam_prepare_app: PICS primed");
}

unsafe fn collect_appids(appids: &mut [u32]) -> usize {
    let appid = APPID.load(Ordering::Relaxed);
    appids[0] = appid;
    let mut count = 1usize;
    if appids.len() <= 1 {
        return count;
    }

    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let create_interface = sym(handle, "CreateInterface");
    if create_interface.is_null() {
        return count;
    }
    let Some((info, profile)) = find_profile(create_interface) else {
        return count;
    };

    let engine_ptr = info.base.wrapping_add(profile.engine_global_offset) as *mut *mut u8;
    let engine = *engine_ptr;
    if engine.is_null() {
        return count;
    }

    let mut index = *(engine.add(0x10b8) as *const i32);
    let entries = *(engine.add(0x10d0) as *const *mut u8);
    if index == -1 || entries.is_null() {
        return count;
    }

    loop {
        let entry = entries.add(index as usize * 0x20);
        let entry_appid = *(entry.add(0x10) as *const u32);

        if entry_appid >= appid {
            if entry_appid == appid {
                let app = *(entry.add(0x18) as *const *mut u8);
                if !app.is_null() {
                    let dlc_count = *(app.add(0xa8) as *const u32);
                    let dlc = *(app.add(0x98) as *const *mut u8);
                    if !dlc.is_null() {
                        for i in 0..dlc_count as usize {
                            if count >= appids.len() {
                                break;
                            }
                            let id = *(dlc.add(0x18 + i * 0x20) as *const u32);
                            if id == 0 || id == appid {
                                continue;
                            }
                            if !appids[..count].contains(&id) {
                                appids[count] = id;
                                count += 1;
                            }
                        }
                    }
                }
            }
            break;
        }

        index = *(entry as *const i32);
        if index == -1 {
            break;
        }
    }

    count
}

unsafe fn steam_prepare_app() {
    let appid = APPID.load(Ordering::Relaxed);
    if appid == 0 {
        log!(
            4,
            "SteamBootstrap",
            "steam_prepare_app: skipping (parent appid=0)"
        );
        return;
    }

    log!(
        4,
        "SteamBootstrap",
        "steam_prepare_app: begin for appid={} (pipe={} user={}, {} total apps)",
        appid,
        PIPE.load(Ordering::Relaxed),
        USER.load(Ordering::Relaxed),
        1
    );

    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let create_interface_ptr = sym(handle, "CreateInterface");
    if create_interface_ptr.is_null() {
        log!(
            6,
            "SteamBootstrap",
            "steam_prepare_app: CreateInterface not found"
        );
        return;
    }
    let create_interface: CreateInterfaceFn = std::mem::transmute(create_interface_ptr);
    let version = CString::new("CLIENTENGINE_INTERFACE_VERSION005").unwrap();
    let mut rc = 0;
    let engine = create_interface(version.as_ptr(), &mut rc);
    if engine.is_null() || rc != 0 {
        log!(
            6,
            "SteamBootstrap",
            "steam_prepare_app: CreateInterface failed (err={})",
            rc
        );
        return;
    }

    let pipe = PIPE.load(Ordering::Relaxed);
    let user_id = USER.load(Ordering::Relaxed);
    let apps =
        vcall!(engine, 0x88, GetInterfaceFn, engine, pipe, user_id).unwrap_or(std::ptr::null_mut());
    let app_mgr = vcall!(engine, 0x158, GetInterfaceFn, engine, pipe, user_id)
        .unwrap_or(std::ptr::null_mut());
    let user =
        vcall!(engine, 0x40, GetInterfaceFn, engine, pipe, user_id).unwrap_or(std::ptr::null_mut());
    let friends =
        vcall!(engine, 0x68, GetInterfaceFn, engine, pipe, user_id).unwrap_or(std::ptr::null_mut());

    log!(
        4,
        "SteamBootstrap",
        "steam_prepare_app: interfaces resolved - IClientApps={:p} \
         IClientAppManager={:p} IClientUser={:p} IClientFriends={:p}",
        apps,
        app_mgr,
        user,
        friends
    );

    if app_mgr.is_null() || user.is_null() {
        log!(
            6,
            "SteamBootstrap",
            "steam_prepare_app: critical interfaces missing (AppManager={:p} User={:p})",
            app_mgr,
            user
        );
        return;
    }

    let breakpad = sym(handle, "Breakpad_SteamSetAppID");
    if !breakpad.is_null() {
        let f: unsafe extern "C" fn(u32) = std::mem::transmute(breakpad);
        f(appid);
        log!(
            4,
            "SteamBootstrap",
            "steam_prepare_app: Breakpad_SteamSetAppID({}) called",
            appid
        );
    }

    let mut appids = [0u32; 256];
    let appid_count = collect_appids(&mut appids);

    if !apps.is_null() {
        if let Some(updated) = vcall!(
            apps,
            0x38,
            RequestInfoFn,
            apps,
            appids.as_mut_ptr(),
            appid_count as c_int
        ) {
            log!(
                4,
                "SteamBootstrap",
                "steam_prepare_app: RequestAppInfoUpdate({} apps) -> {}",
                appid_count,
                updated
            );
        }
    } else {
        log!(
            5,
            "SteamBootstrap",
            "steam_prepare_app: IClientApps not available, skipping PICS update"
        );
    }

    wait_for_pics();

    if !friends.is_null() {
        let _ = vcall!(friends, 0x28, IntIntFn, friends, 1);
        log!(
            4,
            "SteamBootstrap",
            "steam_prepare_app: SetPersonaState(1) called"
        );
    }

    let english = CString::new("english").unwrap();
    let _ = vcall!(user, 0x160, SetLangFn, user, english.as_ptr());
    log!(
        4,
        "SteamBootstrap",
        "steam_prepare_app: SetLanguage(english) called"
    );

    patch_oslist();
    patch_os_type();

    for app in appids.iter().take(appid_count as usize) {
        let mut ok = 0;
        for _ in 0..10 {
            if let Some(result) = vcall!(user, 0x228, OwnershipFn, user, *app, 0) {
                if result & 1 != 0 {
                    ok = 1;
                    break;
                }
            }
        }
        log!(
            if ok != 0 { 4 } else { 5 },
            "SteamBootstrap",
            "steam_prepare_app: BUpdateAppOwnershipTicket({}) -> {}",
            app,
            if ok != 0 {
                "true"
            } else {
                "false (after 10 attempts)"
            }
        );
    }

    drain_callbacks(50);

    // The Android launcher starts the selected executable through Wine.
    log!(
        4,
        "SteamBootstrap",
        "steam_prepare_app: complete for appid={}",
        appid
    );
}

/* ------------------------------------------------------------------ */
/* Overlay IPC                                                        */
/* ------------------------------------------------------------------ */

fn send_all(stream: &mut UnixStream, text: &str) {
    let _ = stream.write_all(text.as_bytes());
}

unsafe fn handle_overlay_client(mut stream: UnixStream, engine: *mut c_void) {
    use std::os::fd::AsRawFd;
    let mut peer: libc::ucred = std::mem::zeroed();
    let mut size = std::mem::size_of_val(&peer) as libc::socklen_t;
    if libc::getsockopt(stream.as_raw_fd(), libc::SOL_SOCKET, libc::SO_PEERCRED,
                        (&mut peer as *mut libc::ucred).cast(), &mut size) != 0
        || peer.uid != libc::geteuid()
    {
        return;
    }
    let reader_stream = match stream.try_clone() {
        Ok(s) => s,
        Err(_) => return,
    };
    let reader = BufReader::new(reader_stream);

    for line in reader.lines() {
        let Ok(mut line) = line else {
            break;
        };
        line = line.trim_matches(|c| c == '\t' || c == ' ').to_string();

        if line.starts_with("PING") {
            send_all(&mut stream, "PONG\n");
        } else if line.starts_with("POLL") {
            let pending = DIALOG_PENDING.swap(false, Ordering::Relaxed);
            let lobby = LOBBY.load(Ordering::Relaxed);
            if pending {
                let dialog = DIALOG.lock().unwrap().clone();
                send_all(&mut stream, &format!("A {} {}\n", dialog, lobby));
            } else {
                send_all(&mut stream, "A - 0\n");
            }
        } else if line.starts_with("LIST") {
            let count = vcall!(engine, 0x38, IntIntFn, engine, 4).unwrap_or(0);
            for i in 0..count {
                let Some(friend_id) = vcall!(engine, 0x50, U64IntIntFn, engine, i, 4) else {
                    continue;
                };
                if friend_id == 0 {
                    continue;
                }
                let relationship = vcall!(engine, 0x68, BoolUserFn, engine, friend_id).unwrap_or(0);
                let name = vcall!(engine, 0x70, StringU64Fn, engine, friend_id)
                    .and_then(|p| cstr_opt(p))
                    .unwrap_or_default();

                let mut game = FriendGameInfo {
                    appid: 0,
                    pad: 0,
                    lobby: 0,
                };
                let have_game = vcall!(
                    engine,
                    0xb8,
                    FriendGameFn,
                    engine,
                    friend_id,
                    &mut game as *mut _ as *mut c_void
                )
                .unwrap_or(0);

                let friend_appid = if have_game != 0 { game.appid } else { 0 };
                let friend_lobby = if have_game != 0 { game.lobby } else { 0 };
                let lobby = LOBBY.load(Ordering::Relaxed);
                let state = if lobby != 0 && friend_lobby == lobby {
                    1
                } else if friend_lobby != 0 {
                    2
                } else if friend_appid == APPID.load(Ordering::Relaxed) {
                    2
                } else {
                    0
                };

                send_all(
                    &mut stream,
                    &format!(
                        "F {} {} {} {} {} {}\n",
                        friend_id, relationship, friend_appid, state, friend_lobby, name
                    ),
                );
            }
            send_all(&mut stream, ".\n");
        } else if line.starts_with("SELF") {
            let mut connect = String::new();
            if let Some(ptr) = vcall!(
                engine,
                0x2e8,
                StringU64StringFn,
                engine,
                STEAMID64.load(Ordering::Relaxed),
                CString::new("connect").unwrap().as_ptr()
            ) {
                connect = cstr_opt(ptr).unwrap_or_default();
            }
            send_all(
                &mut stream,
                &format!(
                    "S {} {} {}\n",
                    STEAMID64.load(Ordering::Relaxed),
                    LOBBY.load(Ordering::Relaxed),
                    connect
                ),
            );
        } else if line.starts_with("INVITE") {
            let rest = line[6..].trim();
            let (friend_text, connect) = rest.split_once(' ').unwrap_or((rest, ""));
            let friend_id: u64 = friend_text.parse().unwrap_or(0);
            if friend_id == 0 {
                send_all(&mut stream, "ERR badsteamid\n");
                continue;
            }
            let mut connect = connect.to_string();
            if connect.is_empty() && DIALOG_PENDING.load(Ordering::Relaxed) {
                connect = DIALOG.lock().unwrap().clone();
            }
            if connect.is_empty() {
                send_all(&mut stream, "ERR noconnect\n");
            } else {
                let c = CString::new(connect).unwrap();
                let ok = vcall!(
                    engine,
                    0x6b8,
                    InviteFn,
                    engine,
                    APPID.load(Ordering::Relaxed),
                    friend_id,
                    c.as_ptr()
                )
                .unwrap_or(0);
                send_all(&mut stream, if ok != 0 { "OK\n" } else { "ERR refused\n" });
            }
        } else if line.starts_with("ACCEPT") {
            let rest = line[6..].trim();
            let mut parts = rest.split_whitespace();
            let lobby: u64 = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
            let friend: u64 = parts.next().and_then(|s| s.parse().ok()).unwrap_or(0);
            if lobby == 0 {
                send_all(&mut stream, "ERR badlobby\n");
            } else {
                let ok = vcall!(
                    engine,
                    0x610,
                    NotifyLobbyFn,
                    engine,
                    APPID.load(Ordering::Relaxed),
                    lobby,
                    friend
                )
                .unwrap_or(0);
                send_all(
                    &mut stream,
                    if ok != 0 {
                        "OK\n"
                    } else {
                        "ERR notdelivered\n"
                    },
                );
            }
        } else if line.starts_with("RPJOIN") {
            let rest = line[6..].trim();
            let (friend_text, connect) = rest.split_once(' ').unwrap_or((rest, ""));
            let friend_id: u64 = friend_text.parse().unwrap_or(0);
            if friend_id == 0 || connect.is_empty() {
                send_all(&mut stream, "ERR noconnect\n");
            } else {
                let c = CString::new(connect).unwrap();
                let ok = vcall!(
                    engine,
                    0x618,
                    NotifyRpFn,
                    engine,
                    APPID.load(Ordering::Relaxed),
                    friend_id,
                    c.as_ptr()
                )
                .unwrap_or(0);
                send_all(
                    &mut stream,
                    if ok != 0 {
                        "OK\n"
                    } else {
                        "ERR notdelivered\n"
                    },
                );
            }
        } else {
            send_all(&mut stream, "ERR unknown\n");
        }
    }
}

fn overlay_thread(engine: usize) {
    let name = std::env::var("SB_OVERLAY_SOCKET")
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| DEFAULT_OVERLAY.to_string());

    let Ok(addr) = SocketAddr::from_abstract_name(name.as_bytes()) else {
        log!(4, "SteamHost", "overlay: invalid socket name");
        return;
    };
    let listener = match UnixListener::bind_addr(&addr) {
        Ok(l) => l,
        Err(e) => {
            log!(
                4,
                "SteamHost",
                "overlay: bind(@{}) failed errno={}",
                name,
                e
            );
            return;
        }
    };
    let _ = listener.set_nonblocking(true);
    OVERLAY_RUNNING.store(true, Ordering::Relaxed);
    log!(
        4,
        "SteamHost",
        "overlay: listening on @{} (appid={})",
        name,
        APPID.load(Ordering::Relaxed)
    );

    while OVERLAY_RUNNING.load(Ordering::Relaxed) && !STOPPING.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((stream, _)) => unsafe { handle_overlay_client(stream, engine as *mut c_void) },
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(200));
            }
            Err(_) => break,
        }
    }
}

/* ------------------------------------------------------------------ */
/* Callback pump                                                      */
/* ------------------------------------------------------------------ */

fn callback_thread() {
    let handle = STEAMCLIENT.load(Ordering::Relaxed);
    let Some((get_cb, free_cb)) = (unsafe { callback_exports(handle) }) else {
        log!(4, "SteamHost", "overlay: callback pump unavailable (missing exports)");
        return;
    };
    let mut logged = 0;

    while CALLBACK_RUNNING.load(Ordering::Relaxed) && !STOPPING.load(Ordering::Relaxed) {
        let mut cb: SteamCallback = unsafe { std::mem::zeroed() };
        if unsafe { get_cb(PIPE.load(Ordering::Relaxed), &mut cb) } == 0 {
            thread::sleep(Duration::from_millis(100));
            continue;
        }

        if cb.id == 0xf6951 && !cb.data.is_null() && cb.data_size > 0x2007 {
            let data = unsafe { std::slice::from_raw_parts(cb.data.cast::<u8>(), 0x2008) };
            let (dialog, lobby) = decode_dialog(data).unwrap();
            *DIALOG.lock().unwrap() = dialog.clone();
            DIALOG_PENDING.store(true, Ordering::Relaxed);
            if lobby != 0 {
                LOBBY.store(lobby, Ordering::Relaxed);
            }
            log!(
                4,
                "SteamHost",
                "overlay: game requested dialog=\"{}\" lobby={}",
                dialog,
                lobby
            );
        } else if logged < 40 {
            logged += 1;
            log!(
                4,
                "SteamHost",
                "overlay: cb id={} size={}",
                cb.id,
                cb.data_size
            );
        }

        unsafe { free_cb(PIPE.load(Ordering::Relaxed)) };
    }
}

/* ------------------------------------------------------------------ */
/* Signals and main                                                   */
/* ------------------------------------------------------------------ */

extern "C" fn on_signal(_sig: c_int) {
    STOPPING.store(true, Ordering::Relaxed);
}

unsafe fn run() -> i32 {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!(
            "usage: {} <appid> <libsteamclient.so> [readyfile]",
            args.first()
                .map(String::as_str)
                .unwrap_or("libsteambootstrap")
        );
        return 2;
    }

    let parent = libc::getppid();
    APPID.store(args[1].parse().unwrap_or(0), Ordering::Relaxed);
    let steamclient_path = &args[2];
    let readyfile = args.get(3).map(String::as_str).filter(|s| !s.is_empty());

    if let Some(path) = readyfile {
        let _ = write_text(path, "WAIT");
    }

    let home = std::env::var("HOME").ok();
    let steam3master = std::env::var("Steam3Master").ok();
    let steamclient_service = std::env::var("SteamClientService").ok();

    if home.is_none() || steam3master.is_none() || steamclient_service.is_none() {
        eprintln!("SteamHost: HOME/Steam3Master/SteamClientService required in env");
        if let Some(path) = readyfile {
            let _ = write_text(path, "FAILED:env");
        }
        return 2;
    }

    let pid = libc::getpid();
    std::env::set_var(
        format!("SteamClientService_{pid}"),
        steamclient_service.unwrap(),
    );
    std::env::set_var(format!("Steam3Master_{pid}"), steam3master.unwrap());
    std::env::set_var("STEAM_SSL_CERT_FILE", CERT_PATH);

    STEAMID64.store(
        std::env::var("SB_STEAMID64")
            .ok()
            .and_then(|v| v.parse().ok())
            .unwrap_or(0),
        Ordering::Relaxed,
    );

    libc::signal(libc::SIGTERM, on_signal as *const () as libc::sighandler_t);
    libc::signal(libc::SIGINT, on_signal as *const () as libc::sighandler_t);

    log!(
        4,
        "SteamHost",
        "start: appid={} lib={}",
        APPID.load(Ordering::Relaxed),
        steamclient_path
    );

    let isolated_session = std::env::var("SB_ISOLATED_SESSION").as_deref() == Ok("1");
    if !isolated_session {
        restore_session();
    }
    preload_siblings(steamclient_path);
    start_steamservice(steamclient_path);

    let handle = dlopen(steamclient_path);
    if handle.is_null() {
        log!(
            4,
            "SteamHost",
            "dlopen failed: {}",
            cstr_opt(libc::dlerror()).unwrap_or_default()
        );
        if let Some(path) = readyfile {
            let _ = write_text(path, "FAILED:dlopen");
        }
        return 1;
    }
    STEAMCLIENT.store(handle, Ordering::Relaxed);

    patch_os_type();

    let create_global_user = sym(handle, "Steam_CreateGlobalUser");
    if create_global_user.is_null() {
        log!(4, "SteamHost", "no Steam_CreateGlobalUser");
        if let Some(path) = readyfile {
            let _ = write_text(path, "FAILED:nosym");
        }
        return 1;
    }

    let create_global_user: unsafe extern "C" fn(*mut c_int) -> c_int =
        std::mem::transmute(create_global_user);
    let mut pipe = 0;
    let user = create_global_user(&mut pipe);
    if user == 0 || pipe == 0 {
        log!(4, "SteamHost", "CreateGlobalUser failed");
        if let Some(path) = readyfile {
            let _ = write_text(path, "FAILED:createuser");
        }
        return 1;
    }
    PIPE.store(pipe, Ordering::Relaxed);
    USER.store(user, Ordering::Relaxed);
    log!(4, "SteamHost", "pipe={} user={}", pipe, user);

    let create_interface = sym(handle, "CreateInterface");
    if create_interface.is_null() {
        if let Some(path) = readyfile {
            let _ = write_text(path, "FAILED:engine");
        }
        return 1;
    }
    let create_interface: CreateInterfaceFn = std::mem::transmute(create_interface);
    let version = CString::new("CLIENTENGINE_INTERFACE_VERSION005").unwrap();
    let mut rc = 0;
    let engine = create_interface(version.as_ptr(), &mut rc);
    if engine.is_null() || rc != 0 {
        if let Some(path) = readyfile {
            let _ = write_text(path, "FAILED:engine");
        }
        return 1;
    }

    let account = std::env::var("SB_ACCOUNT").ok();
    let token = std::env::var("SB_REFRESH_TOKEN").ok();
    let steamid = STEAMID64.load(Ordering::Relaxed);
    let client_user = vcall!(engine, 0x40, GetInterfaceFn, engine, pipe, user)
        .unwrap_or(std::ptr::null_mut());
    let mut result = None;
    if !client_user.is_null() && !logged_on(client_user) {
        if let Some(account) = account.as_deref().filter(|value| !value.is_empty() && steamid != 0) {
            let account_name = CString::new(account).unwrap();
            let has_cached =
                vcall!(client_user, 0x188, HasCachedFn, client_user, account_name.as_ptr())
                    .unwrap_or(0) & 1 != 0;
            // The original host selects the cached account at 0x190. Slot 0x1c0 is
            // the token setter and takes two string pointers, which are never interchangeable.
            let selected_cached = has_cached
                && vcall!(
                    client_user,
                    0x190,
                    SelectCachedAccountFn,
                    client_user,
                    account_name.as_ptr(),
                    1
                )
                .unwrap_or(0) & 1 != 0;
            if selected_cached {
                let call_result = vcall!(client_user, 0x8, LogonFn, client_user, steamid);
                result = if call_result == Some(1) {
                    wait_login_result(Duration::from_secs(12))
                } else {
                    call_result
                };
            }
            if (!selected_cached || !isolated_session)
                && !STOPPING.load(Ordering::Relaxed)
                && !logged_on(client_user)
            {
                if let Some(token) = token.as_deref().filter(|value| !value.is_empty()) {
                    login_with_token(client_user, account, token, steamid);
                    result = wait_login_result(Duration::from_secs(35)).or(result);
                }
            }
        } else {
            result = wait_login_result(Duration::from_secs(35));
        }
    }
    if !logged_on(client_user) {
        let code = result.unwrap_or(0);
        log!(4, "SteamHost", "not logged on, EResult={}", code);
        if let Some(path) = readyfile {
            let _ = write_text(path, &format!("FAILED:login:{code}"));
        }
        return 1;
    }

    log!(4, "SteamHost", "logged on");
    steam_prepare_app();
    log!(
        4,
        "SteamHost",
        "prepared appid={}; serving SteamClientService IPC",
        APPID.load(Ordering::Relaxed)
    );

    let engine_usize = engine as usize;
    OVERLAY_RUNNING.store(true, Ordering::Relaxed);
    if let Err(e) = thread::Builder::new()
        .name("overlay".into())
        .spawn(move || overlay_thread(engine_usize))
    {
        log!(4, "SteamHost", "overlay: pthread_create failed errno={}", e);
    }

    CALLBACK_RUNNING.store(true, Ordering::Relaxed);
    if let Err(e) = thread::Builder::new()
        .name("callbacks".into())
        .spawn(callback_thread)
    {
        log!(
            4,
            "SteamHost",
            "overlay: callback pump pthread_create failed errno={}",
            e
        );
    }

    if let Some(path) = readyfile {
        let _ = write_text(path, "READY");
    }

    while !STOPPING.load(Ordering::Relaxed) && libc::getppid() == parent {
        thread::sleep(Duration::from_secs(1));
    }

    log!(4, "SteamHost", "exiting");
    STOPPING.store(true, Ordering::Relaxed);
    if !isolated_session {
        save_session();
    }
    if let Some(path) = readyfile {
        let _ = write_text(path, "CLOSED");
    }
    0
}

fn main() {
    // Valve workers may still hold client and service resources. Let the kernel
    // release this process without running any shared-library destructors.
    unsafe { libc::_exit(run()) }
}

#[cfg(test)]
#[test]
fn overlay_answers_ping_on_host_socket() {
    let listener = thread::spawn(|| overlay_thread(0));
    let addr = SocketAddr::from_abstract_name(DEFAULT_OVERLAY.as_bytes()).unwrap();
    let start = std::time::Instant::now();
    let mut client = loop {
        if let Ok(client) = UnixStream::connect_addr(&addr) { break client; }
        assert!(start.elapsed() < Duration::from_secs(5));
        thread::sleep(Duration::from_millis(20));
    };
    client.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
    client.write_all(b"PING\n").unwrap();
    let mut line = String::new();
    BufReader::new(&client).read_line(&mut line).unwrap();
    assert_eq!(line, "PONG\n");
    OVERLAY_RUNNING.store(false, Ordering::Relaxed);
    drop(client);
    listener.join().unwrap();
}
