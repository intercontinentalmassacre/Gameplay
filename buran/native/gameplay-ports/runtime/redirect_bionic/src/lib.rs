//! Relocates absolute Android guest paths before the existing Wine WX preload.
//! The next preload retains responsibility for /proc/self/exe and executable memory.

use libc::{c_char, c_int, c_void, mode_t, size_t, ssize_t};
use std::ffi::{CStr, CString};
use std::sync::OnceLock;

const IMAGEFS: &str = concat!("/data/data/", env!("PORT_HOST_ID"), "/files/imagefs");
const LEGACY_ROOTS: &[(&[u8], &[u8])] = &[
    (b"/data/data/com.winlator/files/imagefs", b""),
    (b"/data/user/0/com.winlator/files/imagefs", b""),
    (b"/data/data/com.winlator/files/rootfs", b""),
    (b"/data/user/0/com.winlator/files/rootfs", b""),
    (b"/data/data/app.gamenative/files/imagefs", b""),
    (b"/data/user/0/app.gamenative/files/imagefs", b""),
    (b"/data/data/com.termux/files/usr", b"/usr"),
    (b"/data/user/0/com.termux/files/usr", b"/usr"),
];

fn relocate(path: &[u8], imagefs: &[u8]) -> Option<CString> {
    for (prefix, target) in LEGACY_ROOTS {
        if let Some(rest) = path.strip_prefix(*prefix) {
            if !rest.is_empty() && rest.first() != Some(&b'/') {
                continue;
            }
            let mut result = Vec::with_capacity(imagefs.len() + target.len() + rest.len());
            result.extend_from_slice(imagefs);
            result.extend_from_slice(target);
            result.extend_from_slice(rest);
            return CString::new(result).ok();
        }
    }
    None
}

unsafe fn path_argument(path: *const c_char) -> Option<CString> {
    if path.is_null() {
        return None;
    }
    static ROOT: OnceLock<Option<CString>> = OnceLock::new();
    let root = ROOT.get_or_init(|| {
        let value = libc::getenv(c"BURAN_REDIRECT_IMAGEFS".as_ptr());
        if value.is_null() {
            return None;
        }
        let root = CStr::from_ptr(value);
        let bytes = root.to_bytes();
        (bytes.starts_with(b"/data/") && bytes.ends_with(b"/files/imagefs")).then(|| root.to_owned())
    });
    let imagefs = root.as_deref().map_or(IMAGEFS.as_bytes(), CStr::to_bytes);
    relocate(CStr::from_ptr(path).to_bytes(), imagefs)
}

#[cfg(target_os = "android")]
unsafe fn set_errno() {
    *libc::__errno() = libc::ENOSYS;
}

#[cfg(not(target_os = "android"))]
unsafe fn set_errno() {
    *libc::__errno_location() = libc::ENOSYS;
}

// Paths are valid through the delegated call; nonmatching paths remain the original pointers.
macro_rules! hook {
    ($name:ident($($arg:ident: $ty:ty),*) -> $result:ty, $failure:expr; $($path:ident),+) => {
        #[no_mangle]
        pub unsafe extern "C" fn $name($($arg: $ty),*) -> $result {
            type Next = unsafe extern "C" fn($($ty),*) -> $result;
            static NEXT: OnceLock<Option<Next>> = OnceLock::new();
            let Some(next) = *NEXT.get_or_init(|| {
                let ptr = libc::dlsym(libc::RTLD_NEXT, concat!(stringify!($name), "\0").as_ptr().cast());
                (!ptr.is_null()).then(|| std::mem::transmute::<*mut c_void, Next>(ptr))
            }) else {
                set_errno();
                return $failure;
            };
            $(
                let rewritten = path_argument($path);
                let $path = rewritten.as_ref().map_or($path, |value| value.as_ptr());
            )+
            next($($arg),*)
        }
    };
}

// AArch64 passes the optional mode in the same register as a fixed parameter.
hook!(open(path: *const c_char, flags: c_int, mode: mode_t) -> c_int, -1; path);
hook!(open64(path: *const c_char, flags: c_int, mode: mode_t) -> c_int, -1; path);
hook!(openat(fd: c_int, path: *const c_char, flags: c_int, mode: mode_t) -> c_int, -1; path);
hook!(openat64(fd: c_int, path: *const c_char, flags: c_int, mode: mode_t) -> c_int, -1; path);
hook!(__open_2(path: *const c_char, flags: c_int) -> c_int, -1; path);
hook!(__openat_2(fd: c_int, path: *const c_char, flags: c_int) -> c_int, -1; path);
hook!(access(path: *const c_char, mode: c_int) -> c_int, -1; path);
hook!(stat(path: *const c_char, result: *mut libc::stat) -> c_int, -1; path);
hook!(lstat(path: *const c_char, result: *mut libc::stat) -> c_int, -1; path);
hook!(fstatat(fd: c_int, path: *const c_char, result: *mut libc::stat, flags: c_int) -> c_int, -1; path);
hook!(opendir(path: *const c_char) -> *mut libc::DIR, std::ptr::null_mut(); path);
hook!(readlink(path: *const c_char, buffer: *mut c_char, size: size_t) -> ssize_t, -1; path);
hook!(readlinkat(fd: c_int, path: *const c_char, buffer: *mut c_char, size: size_t) -> ssize_t, -1; path);
hook!(realpath(path: *const c_char, buffer: *mut c_char) -> *mut c_char, std::ptr::null_mut(); path);
// Bionic's dlopen uses the original caller to select a linker namespace.
// Wrapping it would replace that caller, even when the path itself is unchanged.
hook!(mkdir(path: *const c_char, mode: mode_t) -> c_int, -1; path);
hook!(mkdirat(fd: c_int, path: *const c_char, mode: mode_t) -> c_int, -1; path);
hook!(unlink(path: *const c_char) -> c_int, -1; path);
hook!(unlinkat(fd: c_int, path: *const c_char, flags: c_int) -> c_int, -1; path);
hook!(rmdir(path: *const c_char) -> c_int, -1; path);
hook!(rename(source: *const c_char, destination: *const c_char) -> c_int, -1; source, destination);
hook!(renameat(source_fd: c_int, source: *const c_char, destination_fd: c_int, destination: *const c_char) -> c_int, -1; source, destination);

#[cfg(test)]
mod tests {
    use super::relocate;

    #[test]
    fn relocates_only_whole_legacy_imagefs_paths() {
        let root = b"/data/data/app.gameplay.debug/files/imagefs";
        let legacy: [(&[u8], &[u8]); 3] = [
            (b"/data/data/com.winlator/files/imagefs/usr/lib", b"/usr/lib"),
            (b"/data/data/com.termux/files/usr/lib", b"/usr/lib"),
            (b"/data/user/0/app.gamenative/files/imagefs", b""),
        ];
        for (input, suffix) in legacy {
            assert_eq!(relocate(input, root).unwrap().to_bytes(), [root.as_slice(), suffix].concat());
        }
        for path in [
            b"/proc/self/exe".as_slice(),
            b"/data/data/com.winlator/files/imagefs-sibling",
            b"/data/data/app.gameplay.debug/files/imagefs/usr/lib",
            b"./com.winlator/files/imagefs/usr/lib",
        ] {
            assert!(relocate(path, root).is_none(), "{path:?}");
        }
    }
}
