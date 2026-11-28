//! Rust port of `libkgslshim.so`.
//!
//! The original is an Android/AArch64 shim around `ioctl` for old KGSL
//! (Adreno GPU) commands.  Only the two legacy alloc/free commands are
//! translated, and only when the library is mapped from the configured host application.
//!
//! Note on ABI: C declares `ioctl` as variadic.  All arguments are integer or
//! pointer sized and on AArch64 they use the same registers as fixed arguments,
//! so exporting a fixed `(fd, request, arg)` function is ABI-compatible.

use libc::{c_char, c_int, c_void, dlsym, Dl_info, RTLD_NEXT};
use std::ffi::CStr;
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};
use std::sync::OnceLock;

#[cfg(target_os = "android")]
type IoctlRequest = c_int;
#[cfg(not(target_os = "android"))]
type IoctlRequest = libc::c_ulong;

const KGSL_MAGIC_MASK: IoctlRequest = 0xff00;
const KGSL_MAGIC: IoctlRequest = 0x0900;

const KGSL_CMD_ALLOC: IoctlRequest = 0x34;
const KGSL_CMD_FREE: IoctlRequest = 0x35;

const KGSL_NEW_ALLOC: IoctlRequest = 0xc0300945u32 as IoctlRequest;
const KGSL_NEW_ALLOC_INFO: IoctlRequest = 0xc0300947u32 as IoctlRequest;
const KGSL_NEW_OBJ_INFO: IoctlRequest = 0xc0480936u32 as IoctlRequest;
const KGSL_NEW_FREE: IoctlRequest = 0x40200946;

type IoctlFn = unsafe extern "C" fn(c_int, IoctlRequest, ...) -> c_int;

static REAL_IOCTL: OnceLock<Option<IoctlFn>> = OnceLock::new();
static AUTHORIZED: AtomicI32 = AtomicI32::new(-1);
static LEGACY_DENIED: AtomicBool = AtomicBool::new(false);
static LEGACY_FREE_DENIED: AtomicBool = AtomicBool::new(false);
static ADDR_STATE: AtomicI32 = AtomicI32::new(0);

#[repr(C)]
struct KgslAllocOld {
    id: u32,
    size: u32,
    offset: u64,
    flags: u64,
    gpuaddr: u64,
}

#[repr(C)]
struct KgslAllocNew {
    offset: u64,
    size: u64,
    gpuaddr: u64,
    flags: u64,
    id: u64,
    pad: u64,
}

#[repr(C)]
struct KgslInfo {
    gpuaddr: u64,
    pad: [u64; 4],
    id: u64,
}

#[repr(C)]
struct KgslInfoById {
    gpuaddr: u64,
    id: u64,
    pad: [u64; 7],
}

#[repr(C)]
struct KgslFreeOld {
    id: u32,
}

#[repr(C)]
struct KgslFreeNew {
    pad0: u64,
    pad1: u64,
    id: u64,
    pad2: u64,
}

fn real_ioctl() -> Option<IoctlFn> {
    *REAL_IOCTL.get_or_init(|| unsafe {
        let sym = dlsym(RTLD_NEXT, b"ioctl\0".as_ptr() as *const c_char);
        if sym.is_null() {
            None
        } else {
            Some(std::mem::transmute::<*mut c_void, IoctlFn>(sym))
        }
    })
}

unsafe fn errno_ptr() -> *mut c_int {
    #[cfg(target_os = "android")]
    return libc::__errno();
    #[cfg(not(target_os = "android"))]
    return libc::__errno_location();
}

unsafe fn set_errno(value: c_int) {
    *errno_ptr() = value;
}

fn host_library_path(path: &str) -> bool {
    const ID: &str = env!("PORT_HOST_ID");
    path.starts_with('/') && path.split('/').any(|part| {
        part == ID || part.strip_prefix(ID).is_some_and(|suffix| {
            suffix.starts_with('-') && suffix.len() > 1
        })
    })
}

unsafe fn is_authorized() -> bool {
    let cached = AUTHORIZED.load(Ordering::Relaxed);
    if cached >= 0 {
        return cached != 0;
    }

    let mut info = Dl_info {
        dli_fname: std::ptr::null(),
        dli_fbase: std::ptr::null_mut(),
        dli_sname: std::ptr::null(),
        dli_saddr: std::ptr::null_mut(),
    };

    let self_ptr = is_authorized as *const () as *const c_void;
    if libc::dladdr(self_ptr, &mut info) != 0 && !info.dli_fname.is_null() {
        let name = CStr::from_ptr(info.dli_fname).to_string_lossy();
        if host_library_path(&name) {
            AUTHORIZED.store(1, Ordering::Relaxed);
            return true;
        }
    }

    if let Ok(maps) = std::fs::read_to_string("/proc/self/maps") {
        for line in maps.lines() {
            if let Some(path) = line.split_whitespace().nth(5).filter(|p| p.ends_with("/libkgslshim.so")) {
                if host_library_path(path) {
                    AUTHORIZED.store(1, Ordering::Relaxed);
                    return true;
                }
                break;
            }
        }
    }

    AUTHORIZED.store(0, Ordering::Relaxed);
    false
}

unsafe fn query_gpuaddr(fd: c_int, id: u32) -> u64 {
    let real = match real_ioctl() {
        Some(f) => f,
        None => return 0,
    };
    let state = ADDR_STATE.load(Ordering::Relaxed);

    if state == 0 || state == 1 {
        let mut info = KgslInfo {
            gpuaddr: 0,
            pad: [0; 4],
            id: id.into(),
        };
        if real(fd, KGSL_NEW_ALLOC_INFO, &mut info as *mut _ as *mut c_void) == 0 {
            ADDR_STATE.store(1, Ordering::Relaxed);
            return info.gpuaddr;
        }
        if state == 1 {
            return 0;
        }
    }

    if state == 0 || state == 2 {
        let mut by_id = KgslInfoById {
            gpuaddr: 0,
            id: id.into(),
            pad: [0; 7],
        };
        if real(fd, KGSL_NEW_OBJ_INFO, &mut by_id as *mut _ as *mut c_void) == 0 {
            ADDR_STATE.store(2, Ordering::Relaxed);
            return by_id.gpuaddr;
        }
    }

    ADDR_STATE.store(-1, Ordering::Relaxed);
    0
}

unsafe fn translate_alloc(fd: c_int, request: IoctlRequest, old: *mut KgslAllocOld) -> c_int {
    let real = match real_ioctl() {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let old = &mut *old;

    if !LEGACY_DENIED.load(Ordering::Relaxed) {
        let rc = real(fd, request, old as *mut _ as *mut c_void);
        if rc == 0 {
            return 0;
        }
        if *errno_ptr() != libc::EACCES {
            return rc;
        }
        LEGACY_DENIED.store(true, Ordering::Relaxed);
    }

    let mut alloc = KgslAllocNew {
        offset: old.offset,
        size: old.size as u64,
        gpuaddr: 0,
        flags: 0,
        id: 0,
        pad: 0,
    };

    let rc = real(fd, KGSL_NEW_ALLOC, &mut alloc as *mut _ as *mut c_void);
    if rc != 0 {
        return -1;
    }

    old.id = alloc.id as u32;
    old.offset = alloc.offset;
    old.flags = alloc.flags;
    old.gpuaddr = query_gpuaddr(fd, old.id);
    set_errno(0);
    0
}

unsafe fn translate_free(fd: c_int, request: IoctlRequest, old: *mut KgslFreeOld) -> c_int {
    let real = match real_ioctl() {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let old = &mut *old;

    if !LEGACY_FREE_DENIED.load(Ordering::Relaxed) {
        let rc = real(fd, request, old as *mut _ as *mut c_void);
        if rc == 0 {
            return 0;
        }
        if *errno_ptr() != libc::EACCES {
            return rc;
        }
        LEGACY_FREE_DENIED.store(true, Ordering::Relaxed);
    }

    let mut free = KgslFreeNew {
        pad0: 0,
        pad1: 0,
        id: old.id as u64,
        pad2: 0,
    };
    if real(fd, KGSL_NEW_FREE, &mut free as *mut _ as *mut c_void) != 0 {
        return -1;
    }
    set_errno(0);
    0
}

/// Export name matches the C `ioctl` symbol.
///
/// # Safety
/// `arg` must point to the structure required by the requested ioctl, or be
/// null for requests that take no argument.
#[no_mangle]
pub unsafe extern "C" fn ioctl(fd: c_int, request: IoctlRequest, arg: *mut c_void) -> c_int {
    let request = request as u32 as IoctlRequest;
    let real = match real_ioctl() {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };

    if request & KGSL_MAGIC_MASK != KGSL_MAGIC {
        return real(fd, request, arg);
    }

    let command = request & 0xff;

    if command == KGSL_CMD_ALLOC && !arg.is_null() {
        if !is_authorized() {
            return real(fd, request, arg);
        }
        return translate_alloc(fd, request, arg as *mut KgslAllocOld);
    }

    if command == KGSL_CMD_FREE && !arg.is_null() {
        if !is_authorized() {
            return real(fd, request, arg);
        }
        return translate_free(fd, request, arg as *mut KgslFreeOld);
    }

    real(fd, request, arg)
}

#[cfg(test)]
mod host_path_tests {
    use super::host_library_path;

    #[test]
    fn matches_private_and_installed_paths_for_only_the_selected_package() {
        let id = env!("PORT_HOST_ID");
        assert!(host_library_path(&format!("/data/user/0/{id}/lib/libkgslshim.so")));
        assert!(host_library_path(&format!("/data/app/~~abc/{id}-abc123/lib/arm64/libkgslshim.so")));
        assert!(!host_library_path(&format!("/data/app/{id}.other-abc/lib/arm64/libkgslshim.so")));
        assert!(!host_library_path(&format!("/data/app/x{id}/lib/arm64/libkgslshim.so")));
        assert!(!host_library_path(&format!("{id}/libkgslshim.so")));
    }
}
