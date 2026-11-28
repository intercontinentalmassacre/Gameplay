//! Rust port of `libredirect.so` — full FFP implementation.
//!
//! The original is a glibc/AArch64 `LD_PRELOAD` shim for the original
//! Wine/Proton environment.  This port keeps the path rewriting, the FFP
//! negative/positive/directory caches, sentinel descriptors, and the exported
//! wrapper surface.
//!
//! Exported names match the C symbols; open-style entry points receive a
//! fixed mode argument in the same register as the C variadic argument.

#![allow(clippy::missing_safety_doc)]
#![allow(invalid_runtime_symbol_definitions)]

use libc::{c_char, c_int, c_void, size_t, ssize_t};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

const OLD_PREFIX: &str = "com.winlator";
const NEW_PREFIX: &str = env!("PORT_HOST_ID");
const OLD_LONG_PREFIX: &str = "com.winlator/files/rootfs";
const NEW_LONG_PREFIX: &str = concat!(env!("PORT_HOST_ID"), "/files/imagefs");

const IMAGEFS_TMP: &str = concat!("/data/data/", env!("PORT_HOST_ID"), "/files/imagefs/usr/tmp");
const PRELOAD_MARKER: &str = concat!("/data/data/", env!("PORT_HOST_ID"), "/files/imagefs/preload_loaded.txt");
const STATS_PATH: &str = concat!("/data/data/", env!("PORT_HOST_ID"), "/cache/ffp_stats.");
const PLUVIA_PRELOAD: &str =
    concat!("LD_PRELOAD=/data/data/", env!("PORT_HOST_ID"), "/files/imagefs/libpluviagoldberg.so");

const FFP_DIR_MAGIC: u32 = 0x4646_5044;

/* ------------------------------------------------------------------ */
/* Real symbol lookup                                                 */
/* ------------------------------------------------------------------ */

macro_rules! real_fn {
    ($name:literal, $ty:ty) => {{
        static CELL: OnceLock<Option<$ty>> = OnceLock::new();
        *CELL.get_or_init(|| unsafe {
            let sym = libc::dlsym(
                libc::RTLD_NEXT,
                concat!($name, "\0").as_ptr() as *const c_char,
            );
            if sym.is_null() {
                None
            } else {
                Some(std::mem::transmute::<*mut c_void, $ty>(sym))
            }
        })
    }};
}

unsafe fn set_errno(value: c_int) {
    *libc::__errno_location() = value;
}

unsafe fn errno() -> c_int {
    *libc::__errno_location()
}

// Internal I/O must bypass the wrappers, including during STATE initialization.
unsafe fn internal_open(path: *const c_char, flags: c_int, mode: libc::mode_t) -> c_int {
    match real_fn!("open", unsafe extern "C" fn(*const c_char, c_int, ...) -> c_int) {
        Some(f) => f(path, flags, mode),
        None => -1,
    }
}

unsafe fn internal_close(fd: c_int) -> c_int {
    match real_fn!("close", unsafe extern "C" fn(c_int) -> c_int) {
        Some(f) => f(fd),
        None => -1,
    }
}

/* ------------------------------------------------------------------ */
/* Path rewriting                                                     */
/* ------------------------------------------------------------------ */

fn replace_one(input: &str, from: &str, to: &str) -> Option<String> {
    let at = input.find(from)?;
    let mut out = String::with_capacity(input.len() + to.len());
    out.push_str(&input[..at]);
    out.push_str(to);
    out.push_str(&input[at + from.len()..]);
    Some(out)
}

fn replace_substring(input: &str) -> Option<String> {
    replace_one(input, OLD_LONG_PREFIX, NEW_LONG_PREFIX)
        .or_else(|| replace_one(input, OLD_PREFIX, NEW_PREFIX))
}

unsafe fn cstring_path(path: *const c_char) -> Option<CString> {
    if path.is_null() {
        return None;
    }
    let text = CStr::from_ptr(path).to_str().ok()?;
    let rewritten = replace_substring(text);
    if let Some(ref value) = rewritten {
        state().debug_log("rewrite", value);
    }
    rewritten.and_then(|s| CString::new(s).ok())
}

unsafe fn with_path<T>(path: *const c_char, f: impl FnOnce(*const c_char) -> T) -> T {
    let owned = cstring_path(path);
    f(owned.as_ref().map_or(path, |s| s.as_ptr()))
}

/* ------------------------------------------------------------------ */
/* FFP cache state                                                    */
/* ------------------------------------------------------------------ */

#[derive(Clone)]
struct DirEntry {
    dtype: u8,
    ino: u64,
    name: String,
}

struct DirCache {
    expires: Instant,
    entries: Vec<DirEntry>,
}

struct FfpState {
    disabled: bool,
    debug: bool,
    ttl: Duration,
    prefix: String,
    markers: Vec<String>,
    negative: HashMap<String, Instant>,
    posdir: HashMap<String, Instant>,
    nolist: HashMap<String, Instant>,
    dirs: HashMap<String, DirCache>,
    sentinels: HashMap<c_int, String>,
    sentinels_enabled: bool,
    n_neg_hit: u64,
    n_list_hit: u64,
    n_list_miss_answer: u64,
    n_sentinel: u64,
    n_vfat: u64,
    n_real_list: u64,
    last_stats: Instant,
}

impl FfpState {
    fn new() -> Self {
        let enabled = std::env::var("FFP_ENABLE").ok().as_deref() == Some("1");
        let disabled = !enabled || std::env::var("FFP_DISABLE").ok().as_deref() == Some("1");
        let debug = std::env::var("FFP_DEBUG").ok().as_deref() == Some("1");

        let ttl = std::env::var("FFP_TTL_MS")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .filter(|v| *v > 0)
            .map(Duration::from_millis)
            .unwrap_or(Duration::from_millis(5000));

        let prefix = std::env::var("FFP_PREFIX")
            .ok()
            .filter(|v| v.starts_with('/'))
            .unwrap_or_else(|| "/storage/".to_string());

        let markers = std::env::var("FFP_MARKERS")
            .ok()
            .filter(|v| !v.is_empty())
            .map(|v| {
                v.split(';')
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
                    .collect()
            })
            .unwrap_or_else(|| {
                vec![
                    "/storage/".to_string(),
                    "/steamapps/common/".to_string(),
                    "/dosdevices/a:".to_string(),
                ]
            });

        let sentinels_enabled = unsafe {
            let fd = internal_open(c"/proc/self/cmdline".as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC, 0);
            if fd < 0 {
                true
            } else {
                let mut bytes = [0u8; 256];
                let n = libc::read(fd, bytes.as_mut_ptr().cast(), bytes.len());
                internal_close(fd);
                n <= 0 || !bytes[..n as usize].windows(10).any(|w| w == b"wineserver")
            }
        };

        Self {
            disabled,
            debug,
            ttl,
            prefix,
            markers,
            negative: HashMap::new(),
            posdir: HashMap::new(),
            nolist: HashMap::new(),
            dirs: HashMap::new(),
            sentinels: HashMap::new(),
            sentinels_enabled,
            n_neg_hit: 0,
            n_list_hit: 0,
            n_list_miss_answer: 0,
            n_sentinel: 0,
            n_vfat: 0,
            n_real_list: 0,
            last_stats: Instant::now(),
        }
    }

    fn interesting(&self, path: &str) -> bool {
        !self.disabled
            && (path.starts_with(&self.prefix)
                || self.markers.iter().any(|m| path.contains(m.as_str())))
    }

    fn ttl_for(&self, path: &str) -> Duration {
        if path.starts_with(&self.prefix) {
            self.ttl
        } else {
            Duration::from_millis(1500)
        }
    }

    fn expire(&mut self) {
        let now = Instant::now();
        self.negative.retain(|_, deadline| *deadline > now);
        self.posdir.retain(|_, deadline| *deadline > now);
        self.nolist.retain(|_, deadline| *deadline > now);
        self.dirs.retain(|_, dir| dir.expires > now);
    }

    fn is_negative(&self, path: &str) -> bool {
        self.negative.contains_key(path)
    }

    fn note_negative(&mut self, path: &str) {
        if !self.interesting(path) {
            return;
        }
        self.negative
            .insert(path.to_string(), Instant::now() + self.ttl_for(path));
    }

    fn invalidate(&mut self, path: &str) {
        if !self.interesting(path) {
            return;
        }
        self.tab_remove_under(&mut |s| &mut s.negative, path);
        self.tab_remove_under(&mut |s| &mut s.posdir, path);
        self.tab_remove_under(&mut |s| &mut s.nolist, path);
        self.dirs.remove(path);

        if let Some((parent, _)) = split_path(path) {
            self.dirs.remove(&parent);
            self.tab_remove_under(&mut |s| &mut s.negative, &parent);
        }
    }

    fn tab_remove_under(
        &mut self,
        select: &mut dyn FnMut(&mut Self) -> &mut HashMap<String, Instant>,
        prefix: &str,
    ) {
        let table = select(self);
        let child = format!("{prefix}/");
        table.retain(|key, _| key != prefix && !key.starts_with(&child));
    }

    fn dir_lookup(&self, path: &str) -> Option<&DirCache> {
        self.dirs.get(path)
    }

    fn dir_insert(&mut self, path: &str, entries: Vec<DirEntry>, ttl: Duration) {
        self.dirs.insert(
            path.to_string(),
            DirCache {
                expires: Instant::now() + ttl,
                entries,
            },
        );
    }

    fn list_contains(&self, dir: &DirCache, name: &str) -> bool {
        if !name.is_ascii() {
            return false;
        }
        dir.entries
            .iter()
            .any(|entry| entry.name.eq_ignore_ascii_case(name))
    }

    fn known_dir(&self, path: &str) -> bool {
        if self.dirs.contains_key(path) || self.posdir.contains_key(path) {
            return true;
        }
        let Some((parent, name)) = split_path(path) else {
            return false;
        };
        let Some(dir) = self.dirs.get(&parent) else {
            return false;
        };
        dir.entries
            .iter()
            .find(|entry| entry.name.eq_ignore_ascii_case(&name))
            .map(|entry| entry.dtype == libc::DT_DIR as u8)
            .unwrap_or(false)
    }

    fn denied_by_ancestors(&self, path: &str) -> bool {
        let Some(suffix) = path.strip_prefix(&self.prefix) else {
            return false;
        };
        let start = self.prefix.len();
        let mut cursor = suffix.find('/').map(|i| i + start);
        while let Some(pos) = cursor {
            let parent = &path[..pos];
            let rest = &path[pos + 1..];
            let (name, next) = match rest.find('/') {
                Some(i) => (&rest[..i], Some(pos + 1 + i)),
                None => (rest, None),
            };
            if name.is_empty() || name.len() > 255 || parent.len() > 4095 {
                return false;
            }
            let child = format!("{parent}/{name}");
            if self.negative.contains_key(&child) {
                return true;
            }
            if let Some(dir) = self.dirs.get(parent) {
                if !self.list_contains(dir, name) {
                    return true;
                }
            }
            cursor = next;
        }
        false
    }

    fn debug_log(&self, where_: &str, path: &str) {
        if self.debug {
            eprintln!("[libredirect] {where_}: {path}");
        }
    }

    fn dump_stats(&mut self) {
        if self.last_stats.elapsed() < Duration::from_secs(15) {
            return;
        }
        self.last_stats = Instant::now();
        let text = format!(
            "neg_hit={} list_hit={} list_miss_answer={} sentinel={} vfat={} real_list={}\n",
            self.n_neg_hit,
            self.n_list_hit,
            self.n_list_miss_answer,
            self.n_sentinel,
            self.n_vfat,
            self.n_real_list
        );
        let path = CString::new(format!("{}{}", STATS_PATH, unsafe { libc::getpid() })).unwrap();
        let write = real_fn!("write", unsafe extern "C" fn(c_int, *const c_void, size_t) -> ssize_t);
        unsafe {
            let fd = internal_open(path.as_ptr(), libc::O_WRONLY | libc::O_CREAT | libc::O_TRUNC, 0o644);
            if fd >= 0 {
                if let Some(write) = write {
                    write(fd, text.as_ptr().cast(), text.len());
                }
                internal_close(fd);
            }
        }
    }
}

fn split_path(path: &str) -> Option<(String, String)> {
    let slash = path.rfind('/')?;
    if slash == 0 {
        return None;
    }
    let name = &path[slash + 1..];
    if name.is_empty() {
        return None;
    }
    Some((path[..slash].to_string(), name.to_string()))
}

static STATE: OnceLock<Mutex<FfpState>> = OnceLock::new();

extern "C" fn atfork_child() {
    if let Some(lock) = STATE.get() {
        let ptr = lock as *const Mutex<FfpState> as *mut Mutex<FfpState>;
        unsafe {
            std::ptr::write(ptr, Mutex::new(FfpState::new()));
        }
    }
}

fn state() -> MutexGuard<'static, FfpState> {
    let lock = STATE.get_or_init(|| {
        unsafe {
            libc::pthread_atfork(None, None, Some(atfork_child));
        }
        Mutex::new(FfpState::new())
    });
    lock.lock().unwrap_or_else(|e| e.into_inner())
}

/* ------------------------------------------------------------------ */
/* FFP operations                                                     */
/* ------------------------------------------------------------------ */

unsafe fn dir_ensure(path: &str) -> Option<Vec<DirEntry>> {
    {
        let mut guard = state();
        guard.expire();
        if let Some(dir) = guard.dir_lookup(path) {
            return Some(dir.entries.clone());
        }
    }

    let open_dir = real_fn!("opendir", unsafe extern "C" fn(*const c_char) -> *mut libc::DIR)?;
    let read_dir = real_fn!("readdir", unsafe extern "C" fn(*mut libc::DIR) -> *mut libc::dirent)?;
    let close_dir = real_fn!("closedir", unsafe extern "C" fn(*mut libc::DIR) -> c_int)?;
    let cpath = CString::new(path).ok()?;
    let dir = open_dir(cpath.as_ptr());
    if dir.is_null() {
        return None;
    }
    let mut entries = Vec::new();
    loop {
        set_errno(0);
        let entry = read_dir(dir);
        if entry.is_null() || entries.len() == 0x1000 {
            let saved = if entry.is_null() { errno() } else { libc::EOVERFLOW };
            close_dir(dir);
            set_errno(saved);
            if saved != 0 {
                return None;
            }
            break;
        }
        entries.push(DirEntry {
            dtype: (*entry).d_type,
            ino: (*entry).d_ino,
            name: CStr::from_ptr((*entry).d_name.as_ptr()).to_string_lossy().into_owned(),
        });
    }

    if entries.is_empty() {
        return None;
    }

    let ttl = {
        let mut guard = state();
        let ttl = guard.ttl_for(path);
        guard.dir_insert(path, entries.clone(), ttl);
        guard.n_real_list += 1;
        ttl
    };
    let _ = ttl;
    Some(entries)
}

unsafe fn ffp_abs_path(dirfd: c_int, path: *const c_char) -> Option<String> {
    if path.is_null() {
        return None;
    }
    let text = CStr::from_ptr(path).to_string_lossy().into_owned();
    if text.starts_with('/') {
        return Some(text);
    }
    if dirfd == libc::AT_FDCWD {
        return None;
    }
    let guard = state();
    let base = guard.sentinels.get(&dirfd)?;
    Some(format!("{base}/{text}"))
}

unsafe fn ffp_stat(
    dirfd: c_int,
    path: *const c_char,
    st: *mut libc::stat,
    _flags: c_int,
) -> Option<c_int> {
    if path.is_null() {
        return None;
    }

    let text = CStr::from_ptr(path).to_string_lossy().into_owned();
    if text == ".ciopfs" && dirfd != libc::AT_FDCWD {
        let guard = state();
        if guard.sentinels.contains_key(&dirfd) {
            std::ptr::write_bytes(st, 0, 1);
            (*st).st_mode = libc::S_IFREG | 0o444;
            (*st).st_nlink = 1;
            drop(guard);
            return Some(0);
        }
    }

    let absolute = ffp_abs_path(dirfd, path)?;
    {
        let mut guard = state();
        if !guard.interesting(&absolute) {
            return None;
        }
        guard.expire();
        guard.dump_stats();
        if guard.is_negative(&absolute) {
            guard.n_neg_hit += 1;
            set_errno(libc::ENOENT);
            return Some(-1);
        }
        if let Some((parent, name)) = split_path(&absolute) {
            if let Some(dir) = guard.dir_lookup(&parent) {
                if !guard.list_contains(dir, &name) {
                    guard.note_negative(&absolute);
                    guard.n_list_miss_answer += 1;
                    set_errno(libc::ENOENT);
                    return Some(-1);
                }
            }
        }
        if guard.denied_by_ancestors(&absolute) {
            guard.n_list_miss_answer += 1;
            set_errno(libc::ENOENT);
            return Some(-1);
        }
    }

    if miss_from_parent_locked(&absolute) {
        set_errno(libc::ENOENT);
        return Some(-1);
    }
    None
}

unsafe fn miss_from_parent_locked(path: &str) -> bool {
    let Some((parent, name)) = split_path(path) else {
        return false;
    };
    if !name.is_ascii() {
        return false;
    }
    {
        let guard = state();
        if guard.dirs.contains_key(&parent) {
            drop(guard);
        } else if guard.nolist.contains_key(&parent) || guard.negative.contains_key(&parent) {
            return false;
        }
    }

    if let Some(entries) = dir_ensure(&parent) {
        let mut guard = state();
        let dir = DirCache {
            expires: Instant::now(),
            entries,
        };
        if !guard.list_contains(&dir, &name) {
            guard.note_negative(path);
            guard.n_list_miss_answer += 1;
            true
        } else {
            false
        }
    } else {
        let mut guard = state();
        if errno() == libc::ENOENT {
            guard.note_negative(&parent);
        } else {
            let ttl = guard.ttl_for(&parent);
            guard.nolist.insert(parent.clone(), Instant::now() + ttl);
        }
        false
    }
}

unsafe fn sentinel_create(path: &str) -> c_int {
    let real_open = match real_fn!(
        "open",
        unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let dev_null = CString::new("/dev/null").unwrap();
    let fd = real_open(dev_null.as_ptr(), libc::O_CLOEXEC, 0);
    if fd < 0 {
        return -1;
    }
    let mut guard = state();
    guard.sentinels.insert(fd, path.to_string());
    guard.n_sentinel += 1;
    fd
}

unsafe fn ffp_open(dirfd: c_int, path: *const c_char, flags: c_int) -> Option<c_int> {
    if path.is_null() {
        return None;
    }
    let absolute = ffp_abs_path(dirfd, path)?;
    let mut guard = state();
    if !guard.interesting(&absolute) {
        return None;
    }

    if flags & (libc::O_CREAT | libc::O_TRUNC) != 0 {
        guard.invalidate(&absolute);
        return None;
    }

    guard.expire();
    if guard.is_negative(&absolute) {
        guard.n_neg_hit += 1;
        set_errno(libc::ENOENT);
        return Some(-1);
    }

    let probe = (flags & libc::O_ACCMODE) == libc::O_RDONLY
        && (flags & libc::O_NONBLOCK) != 0
        && (flags & libc::O_DIRECTORY) == 0;

    if guard.sentinels_enabled && probe && guard.known_dir(&absolute) {
        drop(guard);
        let fd = sentinel_create(&absolute);
        if fd < 0 {
            return None;
        }
        return Some(fd);
    }

    if let Some((parent, name)) = split_path(&absolute) {
        if let Some(dir) = guard.dir_lookup(&parent) {
            if !guard.list_contains(dir, &name) {
                guard.note_negative(&absolute);
                guard.n_list_miss_answer += 1;
                set_errno(libc::ENOENT);
                return Some(-1);
            }
        }
    }
    if guard.denied_by_ancestors(&absolute) {
        guard.n_list_miss_answer += 1;
        set_errno(libc::ENOENT);
        return Some(-1);
    }
    None
}

struct FfpDirHandle {
    magic: u32,
    pos: usize,
    entries: Vec<DirEntry>,
    ent: libc::dirent,
}

unsafe fn ffp_opendir(path: *const c_char) -> Option<*mut libc::DIR> {
    if path.is_null() {
        return None;
    }
    let text = CStr::from_ptr(path).to_string_lossy().into_owned();
    {
        let mut guard = state();
        if !guard.interesting(&text) {
            return None;
        }
        guard.expire();
        if guard.is_negative(&text) {
            guard.n_neg_hit += 1;
            set_errno(libc::ENOENT);
            return Some(std::ptr::null_mut());
        }
    }

    let Some(entries) = dir_ensure(&text) else {
        if errno() != libc::ENOENT {
            return None;
        }
        state().note_negative(&text);
        set_errno(libc::ENOENT);
        return Some(std::ptr::null_mut());
    };

    let handle = Box::new(FfpDirHandle {
        magic: FFP_DIR_MAGIC,
        pos: 0,
        entries,
        ent: std::mem::zeroed(),
    });
    let mut guard = state();
    guard.n_list_hit += 1;
    Some(Box::into_raw(handle) as *mut libc::DIR)
}

unsafe fn ffp_is_fakedir(dir: *mut libc::DIR) -> bool {
    !dir.is_null() && (*(dir as *const FfpDirHandle)).magic == FFP_DIR_MAGIC
}

unsafe fn ffp_readdir(dir: *mut libc::DIR) -> *mut libc::dirent {
    let handle = &mut *(dir as *mut FfpDirHandle);
    if handle.pos >= handle.entries.len() {
        return std::ptr::null_mut();
    }
    let entry = handle.entries[handle.pos].clone();
    handle.pos += 1;

    std::ptr::write_bytes(&mut handle.ent, 0, 1);
    handle.ent.d_ino = entry.ino;
    handle.ent.d_type = entry.dtype;
    handle.ent.d_reclen = std::mem::size_of::<libc::dirent>() as u16;
    let bytes = entry.name.as_bytes();
    let len = bytes.len().min(handle.ent.d_name.len() - 1);
    std::ptr::copy_nonoverlapping(
        bytes.as_ptr(),
        handle.ent.d_name.as_mut_ptr() as *mut u8,
        len,
    );
    handle.ent.d_name[len] = 0;
    &mut handle.ent
}

unsafe fn ffp_closedir(dir: *mut libc::DIR) -> c_int {
    drop(Box::from_raw(dir as *mut FfpDirHandle));
    0
}

unsafe fn ffp_fstat(fd: c_int, st: *mut libc::stat) -> Option<c_int> {
    let guard = state();
    if !guard.sentinels.contains_key(&fd) {
        return None;
    }
    drop(guard);
    std::ptr::write_bytes(st, 0, 1);
    (*st).st_mode = libc::S_IFDIR | 0o755;
    (*st).st_nlink = 2;
    (*st).st_size = 4096;
    Some(0)
}

unsafe fn ffp_fstatfs(fd: c_int, st: *mut libc::statfs) -> Option<c_int> {
    let guard = state();
    if !guard.sentinels.contains_key(&fd) {
        return None;
    }
    drop(guard);
    std::ptr::write_bytes(st, 0, 1);
    (*st).f_type = 0x6573_5546;
    (*st).f_bsize = 4096;
    (*st).f_namelen = 255;
    Some(0)
}

unsafe fn ffp_note_close(fd: c_int) {
    let mut guard = state();
    guard.sentinels.remove(&fd);
}

unsafe fn ffp_note_open(path: *const c_char, flags: c_int, fd: c_int) {
    if fd >= 0 || errno() != libc::ENOENT || flags & (libc::O_CREAT | libc::O_TRUNC) != 0 {
        return;
    }
    if path.is_null() {
        return;
    }
    let text = CStr::from_ptr(path).to_string_lossy();
    if text.contains('/') {
        state().note_negative(&text);
    }
}

unsafe fn ffp_neg_check(path: *const c_char) -> bool {
    if path.is_null() {
        return false;
    }
    let text = CStr::from_ptr(path).to_string_lossy();
    let mut guard = state();
    guard.expire();
    if guard.is_negative(&text) {
        guard.n_neg_hit += 1;
        set_errno(libc::ENOENT);
        return true;
    }
    false
}

unsafe fn ffp_neg_note(path: *const c_char) {
    if path.is_null() || errno() != libc::ENOENT {
        return;
    }
    let text = CStr::from_ptr(path).to_string_lossy();
    state().note_negative(&text);
}

/* ------------------------------------------------------------------ */
/* File wrappers                                                      */
/* ------------------------------------------------------------------ */

#[no_mangle]
pub unsafe extern "C" fn open(path: *const c_char, flags: c_int, mode: libc::mode_t) -> c_int {
    let rewritten = cstring_path(path);
    let path = rewritten.as_ref().map_or(path, |p| p.as_ptr());
    if flags & (libc::O_CREAT | libc::O_TRUNC) != 0 {
        if let Some(text) = path_text(path) {
            state().invalidate(&text);
        }
    }
    if ffp_neg_check(path) {
        return -1;
    }
    if let Some(rc) = ffp_open(libc::AT_FDCWD, path, flags) {
        return rc;
    }

    let real = match real_fn!(
        "open",
        unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let rc = real(path, flags, mode);
    ffp_note_open(path, flags, rc);
    rc
}

#[no_mangle]
pub unsafe extern "C" fn open64(path: *const c_char, flags: c_int, mode: libc::mode_t) -> c_int {
    open(path, flags, mode)
}

#[no_mangle]
pub unsafe extern "C" fn openat(
    dirfd: c_int,
    path: *const c_char,
    flags: c_int,
    mode: libc::mode_t,
) -> c_int {
    let rewritten = cstring_path(path);
    let path = rewritten.as_ref().map_or(path, |p| p.as_ptr());
    if flags & (libc::O_CREAT | libc::O_TRUNC) != 0 {
        if let Some(text) = path_text(path) {
            state().invalidate(&text);
        }
    }
    if ffp_neg_check(path) {
        return -1;
    }
    if let Some(rc) = ffp_open(dirfd, path, flags) {
        return rc;
    }

    let real = match real_fn!(
        "openat",
        unsafe extern "C" fn(c_int, *const c_char, c_int, libc::mode_t) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let rc = real(dirfd, path, flags, mode);
    ffp_note_open(path, flags, rc);
    rc
}

#[no_mangle]
pub unsafe extern "C" fn openat64(
    dirfd: c_int,
    path: *const c_char,
    flags: c_int,
    mode: libc::mode_t,
) -> c_int {
    openat(dirfd, path, flags, mode)
}

#[no_mangle]
pub unsafe extern "C" fn __open64_2(path: *const c_char, flags: c_int) -> c_int {
    open(path, flags, 0)
}

#[no_mangle]
pub unsafe extern "C" fn fopen(path: *const c_char, mode: *const c_char) -> *mut libc::FILE {
    if ffp_neg_check(path) {
        return std::ptr::null_mut();
    }
    let real = match real_fn!(
        "fopen",
        unsafe extern "C" fn(*const c_char, *const c_char) -> *mut libc::FILE
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return std::ptr::null_mut();
        }
    };
    with_path(path, |p| real(p, mode))
}

#[no_mangle]
pub unsafe extern "C" fn fopen64(path: *const c_char, mode: *const c_char) -> *mut libc::FILE {
    fopen(path, mode)
}

#[no_mangle]
pub unsafe extern "C" fn stat(path: *const c_char, st: *mut libc::stat) -> c_int {
    let rewritten = cstring_path(path);
    let path = rewritten.as_ref().map_or(path, |p| p.as_ptr());
    if ffp_neg_check(path) {
        return -1;
    }
    if let Some(rc) = ffp_stat(libc::AT_FDCWD, path, st, 0) {
        return rc;
    }
    let real = match real_fn!(
        "stat",
        unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let rc = real(path, st);
    if rc == -1 {
        ffp_neg_note(path);
    }
    rc
}

#[no_mangle]
pub unsafe extern "C" fn lstat(path: *const c_char, st: *mut libc::stat) -> c_int {
    let rewritten = cstring_path(path);
    let path = rewritten.as_ref().map_or(path, |p| p.as_ptr());
    if ffp_neg_check(path) {
        return -1;
    }
    if let Some(rc) = ffp_stat(libc::AT_FDCWD, path, st, libc::AT_SYMLINK_NOFOLLOW) {
        return rc;
    }
    let real = match real_fn!(
        "lstat",
        unsafe extern "C" fn(*const c_char, *mut libc::stat) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let rc = real(path, st);
    if rc == -1 {
        ffp_neg_note(path);
    }
    rc
}

#[no_mangle]
pub unsafe extern "C" fn fstat(fd: c_int, st: *mut libc::stat) -> c_int {
    if let Some(rc) = ffp_fstat(fd, st) {
        return rc;
    }
    match real_fn!(
        "fstat",
        unsafe extern "C" fn(c_int, *mut libc::stat) -> c_int
    ) {
        Some(f) => f(fd, st),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn fstatat(
    dirfd: c_int,
    path: *const c_char,
    st: *mut libc::stat,
    flags: c_int,
) -> c_int {
    let rewritten = cstring_path(path);
    let path = rewritten.as_ref().map_or(path, |p| p.as_ptr());
    if ffp_neg_check(path) {
        return -1;
    }
    if let Some(rc) = ffp_stat(dirfd, path, st, flags) {
        return rc;
    }
    let real = match real_fn!(
        "fstatat",
        unsafe extern "C" fn(c_int, *const c_char, *mut libc::stat, c_int) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let rc = real(dirfd, path, st, flags);
    if rc == -1 {
        ffp_neg_note(path);
    }
    rc
}

#[no_mangle]
pub unsafe extern "C" fn fstatfs(fd: c_int, st: *mut libc::statfs) -> c_int {
    if let Some(rc) = ffp_fstatfs(fd, st) {
        return rc;
    }
    match real_fn!(
        "fstatfs",
        unsafe extern "C" fn(c_int, *mut libc::statfs) -> c_int
    ) {
        Some(f) => f(fd, st),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn access(path: *const c_char, mode: c_int) -> c_int {
    if ffp_neg_check(path) {
        return -1;
    }
    let real = match real_fn!(
        "access",
        unsafe extern "C" fn(*const c_char, c_int) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let rc = with_path(path, |p| real(p, mode));
    ffp_neg_note(path);
    rc
}

#[no_mangle]
pub unsafe extern "C" fn unlink(path: *const c_char) -> c_int {
    if let Some(text) = path_text(path) {
        state().invalidate(&text);
    }
    match real_fn!("unlink", unsafe extern "C" fn(*const c_char) -> c_int) {
        Some(f) => with_path(path, |p| f(p)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn rename(old: *const c_char, new: *const c_char) -> c_int {
    if let Some(text) = path_text(old) {
        state().invalidate(&text);
    }
    if let Some(text) = path_text(new) {
        state().invalidate(&text);
    }
    let real = match real_fn!(
        "rename",
        unsafe extern "C" fn(*const c_char, *const c_char) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let old_owned = cstring_path(old);
    let new_owned = cstring_path(new);
    real(
        old_owned.as_ref().map_or(old, |s| s.as_ptr()),
        new_owned.as_ref().map_or(new, |s| s.as_ptr()),
    )
}

#[no_mangle]
pub unsafe extern "C" fn mkdir(path: *const c_char, mode: libc::mode_t) -> c_int {
    if let Some(text) = path_text(path) {
        state().invalidate(&text);
    }
    match real_fn!(
        "mkdir",
        unsafe extern "C" fn(*const c_char, libc::mode_t) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(p, mode)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn rmdir(path: *const c_char) -> c_int {
    if let Some(text) = path_text(path) {
        state().invalidate(&text);
    }
    match real_fn!("rmdir", unsafe extern "C" fn(*const c_char) -> c_int) {
        Some(f) => with_path(path, |p| f(p)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn chdir(path: *const c_char) -> c_int {
    match real_fn!("chdir", unsafe extern "C" fn(*const c_char) -> c_int) {
        Some(f) => with_path(path, |p| f(p)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn symlink(target: *const c_char, link: *const c_char) -> c_int {
    let real = match real_fn!(
        "symlink",
        unsafe extern "C" fn(*const c_char, *const c_char) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let target_owned = cstring_path(target);
    let link_owned = cstring_path(link);
    real(
        target_owned.as_ref().map_or(target, |s| s.as_ptr()),
        link_owned.as_ref().map_or(link, |s| s.as_ptr()),
    )
}

#[no_mangle]
pub unsafe extern "C" fn shm_open(name: *const c_char, flags: c_int, mode: libc::mode_t) -> c_int {
    let text = path_text(name).unwrap_or_default();
    if text.starts_with("/wine") {
        let full = format!("{IMAGEFS_TMP}{text}");
        if let Ok(c) = CString::new(full) {
            let real = match real_fn!(
                "open",
                unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
            ) {
                Some(f) => f,
                None => {
                    set_errno(libc::ENOSYS);
                    return -1;
                }
            };
            return real(c.as_ptr(), flags, mode);
        }
        set_errno(libc::ENOMEM);
        return -1;
    }
    match real_fn!(
        "shm_open",
        unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
    ) {
        Some(f) => with_path(name, |p| f(p, flags, mode)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn shm_unlink(name: *const c_char) -> c_int {
    let text = path_text(name).unwrap_or_default();
    if text.starts_with("/wine") {
        let full = format!("{IMAGEFS_TMP}{text}");
        if let Ok(c) = CString::new(full) {
            let real = match real_fn!("unlink", unsafe extern "C" fn(*const c_char) -> c_int) {
                Some(f) => f,
                None => {
                    set_errno(libc::ENOSYS);
                    return -1;
                }
            };
            return real(c.as_ptr());
        }
        set_errno(libc::ENOMEM);
        return -1;
    }
    match real_fn!("shm_unlink", unsafe extern "C" fn(*const c_char) -> c_int) {
        Some(f) => with_path(name, |p| f(p)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

/* ------------------------------------------------------------------ */
/* Directory wrappers                                                 */
/* ------------------------------------------------------------------ */

#[no_mangle]
pub unsafe extern "C" fn opendir(path: *const c_char) -> *mut libc::DIR {
    let rewritten = cstring_path(path);
    let path = rewritten.as_ref().map_or(path, |p| p.as_ptr());
    if ffp_neg_check(path) {
        return std::ptr::null_mut();
    }
    if let Some(dir) = ffp_opendir(path) {
        return dir;
    }
    match real_fn!(
        "opendir",
        unsafe extern "C" fn(*const c_char) -> *mut libc::DIR
    ) {
        Some(f) => {
            let dir = f(path);
            if dir.is_null() {
                ffp_neg_note(path);
            }
            dir
        }
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn readdir(dir: *mut libc::DIR) -> *mut libc::dirent {
    if ffp_is_fakedir(dir) {
        return ffp_readdir(dir);
    }
    let real = match real_fn!(
        "readdir",
        unsafe extern "C" fn(*mut libc::DIR) -> *mut libc::dirent
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return std::ptr::null_mut();
        }
    };
    let entry = real(dir);
    if !entry.is_null() {
        let name = CStr::from_ptr((*entry).d_name.as_ptr()).to_string_lossy();
        if name.contains(OLD_PREFIX) {
            if let Some(rewritten) = replace_substring(&name) {
                if let Ok(c) = CString::new(rewritten) {
                    let bytes = c.as_bytes_with_nul();
                    let dst = (*entry).d_name.as_mut_ptr() as *mut u8;
                    let len = bytes.len().min((*entry).d_name.len());
                    std::ptr::copy_nonoverlapping(bytes.as_ptr(), dst, len);
                    *dst.add(len - 1) = 0;
                }
            }
        }
    }
    entry
}

#[no_mangle]
pub unsafe extern "C" fn readdir64(dir: *mut libc::DIR) -> *mut libc::dirent64 {
    if ffp_is_fakedir(dir) {
        return ffp_readdir(dir).cast();
    }
    match real_fn!("readdir64", unsafe extern "C" fn(*mut libc::DIR) -> *mut libc::dirent64) {
        Some(f) => f(dir),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn closedir(dir: *mut libc::DIR) -> c_int {
    if ffp_is_fakedir(dir) {
        return ffp_closedir(dir);
    }
    match real_fn!("closedir", unsafe extern "C" fn(*mut libc::DIR) -> c_int) {
        Some(f) => f(dir),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn rewinddir(dir: *mut libc::DIR) {
    if ffp_is_fakedir(dir) {
        (*(dir as *mut FfpDirHandle)).pos = 0;
        return;
    }
    if let Some(f) = real_fn!("rewinddir", unsafe extern "C" fn(*mut libc::DIR)) {
        f(dir);
    }
}

#[no_mangle]
pub unsafe extern "C" fn fdopendir(fd: c_int) -> *mut libc::DIR {
    match real_fn!("fdopendir", unsafe extern "C" fn(c_int) -> *mut libc::DIR) {
        Some(f) => f(fd),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

/* ------------------------------------------------------------------ */
/* cwd / realpath                                                     */
/* ------------------------------------------------------------------ */

#[no_mangle]
pub unsafe extern "C" fn getcwd(buffer: *mut c_char, size: size_t) -> *mut c_char {
    let real = match real_fn!(
        "getcwd",
        unsafe extern "C" fn(*mut c_char, size_t) -> *mut c_char
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return std::ptr::null_mut();
        }
    };
    let result = real(buffer, size);
    if result.is_null() {
        return result;
    }
    let text = CStr::from_ptr(result).to_string_lossy();
    let Some(rewritten) = replace_substring(&text) else {
        return result;
    };
    let Ok(c) = CString::new(rewritten) else {
        return result;
    };
    let bytes = c.as_bytes_with_nul();
    if buffer.is_null() {
        libc::free(result as *mut c_void);
        return libc::strdup(c.as_ptr());
    }
    let len = bytes.len().min(size);
    std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer as *mut u8, len);
    *buffer.add(len - 1) = 0;
    buffer
}

#[no_mangle]
pub unsafe extern "C" fn realpath(path: *const c_char, resolved: *mut c_char) -> *mut c_char {
    let real = match real_fn!(
        "realpath",
        unsafe extern "C" fn(*const c_char, *mut c_char) -> *mut c_char
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return std::ptr::null_mut();
        }
    };
    let result = with_path(path, |p| real(p, resolved));
    if result.is_null() {
        return result;
    }
    let text = CStr::from_ptr(result).to_string_lossy();
    let Some(rewritten) = replace_substring(&text) else {
        return result;
    };
    let Ok(c) = CString::new(rewritten) else {
        return result;
    };
    if resolved.is_null() {
        libc::free(result as *mut c_void);
        return libc::strdup(c.as_ptr());
    }
    std::ptr::copy_nonoverlapping(
        c.as_bytes_with_nul().as_ptr(),
        resolved as *mut u8,
        c.as_bytes_with_nul().len(),
    );
    resolved
}

/* ------------------------------------------------------------------ */
/* 64-bit and glibc internal stat wrappers                            */
/* ------------------------------------------------------------------ */

#[no_mangle]
pub unsafe extern "C" fn stat64(path: *const c_char, st: *mut libc::stat64) -> c_int {
    match real_fn!(
        "stat64",
        unsafe extern "C" fn(*const c_char, *mut libc::stat64) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(p, st)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn lstat64(path: *const c_char, st: *mut libc::stat64) -> c_int {
    match real_fn!(
        "lstat64",
        unsafe extern "C" fn(*const c_char, *mut libc::stat64) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(p, st)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn fstat64(fd: c_int, st: *mut libc::stat64) -> c_int {
    match real_fn!(
        "fstat64",
        unsafe extern "C" fn(c_int, *mut libc::stat64) -> c_int
    ) {
        Some(f) => f(fd, st),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn fstatat64(
    dirfd: c_int,
    path: *const c_char,
    st: *mut libc::stat64,
    flags: c_int,
) -> c_int {
    match real_fn!(
        "fstatat64",
        unsafe extern "C" fn(c_int, *const c_char, *mut libc::stat64, c_int) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(dirfd, p, st, flags)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn __xstat(ver: c_int, path: *const c_char, st: *mut libc::stat) -> c_int {
    let _ = ver;
    stat(path, st)
}

#[no_mangle]
pub unsafe extern "C" fn __lxstat(ver: c_int, path: *const c_char, st: *mut libc::stat) -> c_int {
    let _ = ver;
    lstat(path, st)
}

#[no_mangle]
pub unsafe extern "C" fn __xstat64(
    ver: c_int,
    path: *const c_char,
    st: *mut libc::stat64,
) -> c_int {
    let _ = ver;
    stat64(path, st)
}

#[no_mangle]
pub unsafe extern "C" fn __lxstat64(
    ver: c_int,
    path: *const c_char,
    st: *mut libc::stat64,
) -> c_int {
    let _ = ver;
    lstat64(path, st)
}

#[no_mangle]
pub unsafe extern "C" fn __fxstatat(
    ver: c_int,
    dirfd: c_int,
    path: *const c_char,
    st: *mut libc::stat,
    flags: c_int,
) -> c_int {
    let _ = ver;
    fstatat(dirfd, path, st, flags)
}

#[no_mangle]
pub unsafe extern "C" fn __fxstatat64(
    ver: c_int,
    dirfd: c_int,
    path: *const c_char,
    st: *mut libc::stat64,
    flags: c_int,
) -> c_int {
    let _ = ver;
    fstatat64(dirfd, path, st, flags)
}

#[no_mangle]
pub unsafe extern "C" fn _IO_file_stat(file: *mut libc::FILE, st: *mut c_void) -> c_int {
    match real_fn!(
        "_IO_file_stat",
        unsafe extern "C" fn(*mut libc::FILE, *mut c_void) -> c_int
    ) {
        Some(f) => f(file, st),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

/* ------------------------------------------------------------------ */
/* Process spawning                                                   */
/* ------------------------------------------------------------------ */

unsafe fn is_wineserver(path: *const c_char) -> bool {
    if path.is_null() {
        return false;
    }
    CStr::from_ptr(path).to_string_lossy().rsplit('/').next() == Some("wineserver")
}

unsafe fn env_with_preload(envp: *const *const c_char) -> (Vec<CString>, Vec<*const c_char>) {
    let mut out = Vec::new();
    if !envp.is_null() {
        let mut i = 0;
        while !(*envp.add(i)).is_null() {
            let item = CStr::from_ptr(*envp.add(i));
            if !item.to_bytes().starts_with(b"LD_PRELOAD=") {
                out.push(item.to_owned());
            }
            i += 1;
        }
    }
    out.push(CString::new(PLUVIA_PRELOAD).unwrap());
    let mut ptrs: Vec<*const c_char> = out.iter().map(|s| s.as_ptr()).collect();
    ptrs.push(std::ptr::null());
    (out, ptrs)
}

#[no_mangle]
pub unsafe extern "C" fn execve(
    path: *const c_char,
    argv: *const *const c_char,
    envp: *const *const c_char,
) -> c_int {
    let real = match real_fn!(
        "execve",
        unsafe extern "C" fn(*const c_char, *const *const c_char, *const *const c_char) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    if is_wineserver(path) {
        let source = if envp.is_null() {
            libc::environ as *const *const c_char
        } else {
            envp
        };
        let (_owned, ptrs) = env_with_preload(source);
        return real(path, argv, ptrs.as_ptr());
    }
    real(path, argv, envp)
}

#[no_mangle]
pub unsafe extern "C" fn execv(path: *const c_char, argv: *const *const c_char) -> c_int {
    match real_fn!(
        "execv",
        unsafe extern "C" fn(*const c_char, *const *const c_char) -> c_int
    ) {
        Some(f) => f(path, argv),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn execvp(path: *const c_char, argv: *const *const c_char) -> c_int {
    match real_fn!(
        "execvp",
        unsafe extern "C" fn(*const c_char, *const *const c_char) -> c_int
    ) {
        Some(f) => f(path, argv),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn posix_spawn(
    pid: *mut libc::pid_t,
    path: *const c_char,
    file_actions: *const libc::posix_spawn_file_actions_t,
    attrp: *const libc::posix_spawnattr_t,
    argv: *const *mut c_char,
    envp: *const *mut c_char,
) -> c_int {
    let real = match real_fn!(
        "posix_spawn",
        unsafe extern "C" fn(
            *mut libc::pid_t,
            *const c_char,
            *const libc::posix_spawn_file_actions_t,
            *const libc::posix_spawnattr_t,
            *const *mut c_char,
            *const *mut c_char,
        ) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    if is_wineserver(path) {
        let source = if envp.is_null() {
            libc::environ as *const *mut c_char
        } else {
            envp
        };
        let (owned, ptrs) = env_with_preload(source as *const *const c_char);
        let ptrs: Vec<*mut c_char> = ptrs.iter().map(|p| *p as *mut c_char).collect();
        let rc = real(pid, path, file_actions, attrp, argv, ptrs.as_ptr());
        drop(owned);
        return rc;
    }
    real(pid, path, file_actions, attrp, argv, envp)
}

#[no_mangle]
pub unsafe extern "C" fn posix_spawnp(
    pid: *mut libc::pid_t,
    path: *const c_char,
    file_actions: *const libc::posix_spawn_file_actions_t,
    attrp: *const libc::posix_spawnattr_t,
    argv: *const *mut c_char,
    envp: *const *mut c_char,
) -> c_int {
    let real = match real_fn!(
        "posix_spawnp",
        unsafe extern "C" fn(
            *mut libc::pid_t,
            *const c_char,
            *const libc::posix_spawn_file_actions_t,
            *const libc::posix_spawnattr_t,
            *const *mut c_char,
            *const *mut c_char,
        ) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    if is_wineserver(path) {
        let source = if envp.is_null() {
            libc::environ as *const *mut c_char
        } else {
            envp
        };
        let (owned, ptrs) = env_with_preload(source as *const *const c_char);
        let ptrs: Vec<*mut c_char> = ptrs.iter().map(|p| *p as *mut c_char).collect();
        let rc = real(pid, path, file_actions, attrp, argv, ptrs.as_ptr());
        drop(owned);
        return rc;
    }
    real(pid, path, file_actions, attrp, argv, envp)
}

/* ------------------------------------------------------------------ */
/* Misc wrappers                                                      */
/* ------------------------------------------------------------------ */

#[no_mangle]
pub unsafe extern "C" fn close(fd: c_int) -> c_int {
    ffp_note_close(fd);
    match real_fn!("close", unsafe extern "C" fn(c_int) -> c_int) {
        Some(f) => f(fd),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn write(fd: c_int, buffer: *const c_void, size: size_t) -> ssize_t {
    let real = match real_fn!(
        "write",
        unsafe extern "C" fn(c_int, *const c_void, size_t) -> ssize_t
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    if buffer.is_null() || size == 0 {
        return real(fd, buffer, size);
    }
    let bytes = std::slice::from_raw_parts(buffer as *const u8, size);
    if let Ok(text) = std::str::from_utf8(bytes) {
        if let Some(rewritten) = replace_substring(text) {
            return real(fd, rewritten.as_ptr() as *const c_void, rewritten.len());
        }
    }
    real(fd, buffer, size)
}

#[no_mangle]
pub unsafe extern "C" fn dlopen(path: *const c_char, flags: c_int) -> *mut c_void {
    match real_fn!(
        "dlopen",
        unsafe extern "C" fn(*const c_char, c_int) -> *mut c_void
    ) {
        Some(f) => with_path(path, |p| f(p, flags)),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn connect(
    fd: c_int,
    addr: *const libc::sockaddr,
    len: libc::socklen_t,
) -> c_int {
    let real = match real_fn!(
        "connect",
        unsafe extern "C" fn(c_int, *const libc::sockaddr, libc::socklen_t) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    if addr.is_null() || (*addr).sa_family as c_int != libc::AF_UNIX {
        return real(fd, addr, len);
    }
    let un = &*(addr as *const libc::sockaddr_un);
    let path = CStr::from_ptr(un.sun_path.as_ptr()).to_string_lossy();
    let Some(rewritten) = replace_substring(&path) else {
        return real(fd, addr, len);
    };
    let Ok(c) = CString::new(rewritten) else {
        return real(fd, addr, len);
    };
    let mut local: libc::sockaddr_un = std::mem::zeroed();
    local.sun_family = libc::AF_UNIX as libc::sa_family_t;
    let bytes = c.as_bytes_with_nul();
    let copy_len = bytes.len().min(local.sun_path.len());
    std::ptr::copy_nonoverlapping(
        bytes.as_ptr(),
        local.sun_path.as_mut_ptr() as *mut u8,
        copy_len,
    );
    let new_len = (std::mem::size_of::<libc::sa_family_t>() + copy_len) as libc::socklen_t;
    real(fd, &local as *const _ as *const libc::sockaddr, new_len)
}

#[no_mangle]
pub unsafe extern "C" fn vasprintf(
    out: *mut *mut c_char,
    fmt: *const c_char,
    ap: *mut c_void,
) -> c_int {
    let real = match real_fn!(
        "vasprintf",
        unsafe extern "C" fn(*mut *mut c_char, *const c_char, *mut c_void) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    let rc = real(out, fmt, ap);
    if rc >= 0 && !out.is_null() && !(*out).is_null() {
        let text = CStr::from_ptr(*out).to_string_lossy();
        if let Some(rewritten) = replace_substring(&text) {
            if let Ok(c) = CString::new(rewritten) {
                libc::free(*out as *mut c_void);
                *out = c.into_raw();
            }
        }
    }
    rc
}

// Preserve the caller's variadic registers and stack until C creates the va_list.
#[unsafe(naked)]
#[no_mangle]
pub unsafe extern "C" fn asprintf() {
    #[cfg(target_arch = "aarch64")]
    core::arch::naked_asm!("b {}", sym gameplay_asprintf);
    #[cfg(target_arch = "x86_64")]
    core::arch::naked_asm!("jmp {}", sym gameplay_asprintf);
}

extern "C" {
    fn gameplay_asprintf(out: *mut *mut c_char, fmt: *const c_char, ...) -> c_int;
}

#[no_mangle]
pub unsafe extern "C" fn statx(
    dirfd: c_int,
    path: *const c_char,
    flags: c_int,
    mask: libc::c_uint,
    st: *mut c_void,
) -> c_int {
    let real = match real_fn!(
        "statx",
        unsafe extern "C" fn(c_int, *const c_char, c_int, libc::c_uint, *mut c_void) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    with_path(path, |p| real(dirfd, p, flags, mask, st))
}

#[repr(C)]
pub struct OpenHow {
    flags: u64,
    mode: u64,
    resolve: u64,
}

#[no_mangle]
pub unsafe extern "C" fn openat2(
    dirfd: c_int,
    path: *const c_char,
    how: *const OpenHow,
    size: size_t,
) -> c_int {
    let real = match real_fn!(
        "openat2",
        unsafe extern "C" fn(c_int, *const c_char, *const OpenHow, size_t) -> c_int
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return -1;
        }
    };
    with_path(path, |p| real(dirfd, p, how, size))
}

#[repr(C)]
pub struct CookieIoFunctions {
    read: Option<unsafe extern "C" fn(*mut c_void, *mut c_char, size_t) -> ssize_t>,
    write: Option<unsafe extern "C" fn(*mut c_void, *const c_char, size_t) -> ssize_t>,
    seek: Option<unsafe extern "C" fn(*mut c_void, *mut i64, c_int) -> c_int>,
    close: Option<unsafe extern "C" fn(*mut c_void) -> c_int>,
}

#[no_mangle]
pub unsafe extern "C" fn fopencookie(
    cookie: *mut c_void,
    mode: *const c_char,
    io: CookieIoFunctions,
) -> *mut libc::FILE {
    let real = match real_fn!(
        "fopencookie",
        unsafe extern "C" fn(
            *mut c_void,
            *const c_char,
            CookieIoFunctions,
        ) -> *mut libc::FILE
    ) {
        Some(f) => f,
        None => {
            set_errno(libc::ENOSYS);
            return std::ptr::null_mut();
        }
    };
    real(cookie, mode, io)
}

#[no_mangle]
pub unsafe extern "C" fn fdopen(fd: c_int, mode: *const c_char) -> *mut libc::FILE {
    match real_fn!(
        "fdopen",
        unsafe extern "C" fn(c_int, *const c_char) -> *mut libc::FILE
    ) {
        Some(f) => f(fd, mode),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn popen(command: *const c_char, mode: *const c_char) -> *mut libc::FILE {
    match real_fn!(
        "popen",
        unsafe extern "C" fn(*const c_char, *const c_char) -> *mut libc::FILE
    ) {
        Some(f) => with_path(command, |p| f(p, mode)),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn fts64_open(
    argv: *mut *mut c_char,
    options: c_int,
    compar: *mut c_void,
) -> *mut c_void {
    match real_fn!(
        "fts64_open",
        unsafe extern "C" fn(*mut *mut c_char, c_int, *mut c_void) -> *mut c_void
    ) {
        Some(f) => f(argv, options, compar),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_open(path: *const c_char, flags: c_int, mode: libc::mode_t) -> c_int {
    match real_fn!(
        "my_open",
        unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(p, flags, mode)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_fopen(path: *const c_char, mode: *const c_char) -> *mut libc::FILE {
    match real_fn!(
        "my_fopen",
        unsafe extern "C" fn(*const c_char, *const c_char) -> *mut libc::FILE
    ) {
        Some(f) => with_path(path, |p| f(p, mode)),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_dlopen(path: *const c_char, flags: c_int) -> *mut c_void {
    match real_fn!(
        "my_dlopen",
        unsafe extern "C" fn(*const c_char, c_int) -> *mut c_void
    ) {
        Some(f) => with_path(path, |p| f(p, flags)),
        None => {
            set_errno(libc::ENOSYS);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_stat(path: *const c_char, st: *mut c_void) -> c_int {
    match real_fn!(
        "my_stat",
        unsafe extern "C" fn(*const c_char, *mut c_void) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(p, st)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_lstat(path: *const c_char, st: *mut c_void) -> c_int {
    match real_fn!(
        "my_lstat",
        unsafe extern "C" fn(*const c_char, *mut c_void) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(p, st)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_fstat(fd: c_int, st: *mut c_void) -> c_int {
    match real_fn!(
        "my_fstat",
        unsafe extern "C" fn(c_int, *mut c_void) -> c_int
    ) {
        Some(f) => f(fd, st),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_fstatat(
    dirfd: c_int,
    path: *const c_char,
    st: *mut c_void,
    flags: c_int,
) -> c_int {
    match real_fn!(
        "my_fstatat",
        unsafe extern "C" fn(c_int, *const c_char, *mut c_void, c_int) -> c_int
    ) {
        Some(f) => with_path(path, |p| f(dirfd, p, st, flags)),
        None => {
            set_errno(libc::ENOSYS);
            -1
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn my_XOpenDisplay(name: *const c_char) -> *mut c_void {
    match real_fn!(
        "my_XOpenDisplay",
        unsafe extern "C" fn(*const c_char) -> *mut c_void
    ) {
        Some(f) => f(name),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn XOpenDisplay(name: *const c_char) -> *mut c_void {
    match real_fn!(
        "XOpenDisplay",
        unsafe extern "C" fn(*const c_char) -> *mut c_void
    ) {
        Some(f) => f(name),
        None => std::ptr::null_mut(),
    }
}

unsafe fn path_text(path: *const c_char) -> Option<String> {
    if path.is_null() {
        None
    } else {
        Some(CStr::from_ptr(path).to_string_lossy().into_owned())
    }
}

/* ------------------------------------------------------------------ */
/* Constructor                                                        */
/* ------------------------------------------------------------------ */

unsafe extern "C" fn pluviagoldberg_on_load() {
    let open_real = match real_fn!(
        "open",
        unsafe extern "C" fn(*const c_char, c_int, libc::mode_t) -> c_int
    ) {
        Some(f) => f,
        None => return,
    };
    let write_real = match real_fn!(
        "write",
        unsafe extern "C" fn(c_int, *const c_void, size_t) -> ssize_t
    ) {
        Some(f) => f,
        None => return,
    };
    let close_real = match real_fn!("close", unsafe extern "C" fn(c_int) -> c_int) {
        Some(f) => f,
        None => return,
    };
    let path = CString::new(PRELOAD_MARKER).unwrap();
    let fd = open_real(
        path.as_ptr(),
        libc::O_WRONLY | libc::O_CREAT | libc::O_APPEND,
        0o644,
    );
    if fd < 0 {
        return;
    }
    let text = b"[INIT] libpluviagoldberg.so loaded\n";
    write_real(fd, text.as_ptr() as *const c_void, text.len());
    close_real(fd);
}

#[used]
#[link_section = ".init_array"]
static INIT_ARRAY: unsafe extern "C" fn() = pluviagoldberg_on_load;
