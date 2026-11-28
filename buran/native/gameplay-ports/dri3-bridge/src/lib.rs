//! Session-local adapter for Winlator's private DRI3 buffer transports.

mod image;
mod readback;

use image::AndroidBuffer;
use libc::{c_char, c_int, c_void};
use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::fs::File;
use std::os::fd::{FromRawFd, IntoRawFd, OwnedFd};
use std::os::unix::fs::FileExt;
use std::ptr::{null, null_mut};
use std::sync::atomic::{AtomicU8, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, Once, OnceLock};

type Conn = *mut c_void;

#[repr(C)]
pub struct Cookie {
    sequence: u32,
}

#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn log(message: impl AsRef<str>) {
    if let Ok(message) = CString::new(message.as_ref()) {
        unsafe { __android_log_write(4, c"BuranDri3".as_ptr(), message.as_ptr()) };
    }
}

fn trace_only() -> bool {
    static TRACE: OnceLock<bool> = OnceLock::new();
    *TRACE.get_or_init(|| std::env::var("BURAN_DRI3_BRIDGE").as_deref() == Ok("trace"))
}

// Wine's wrapper may have a local dynamic-library group; use its XCB
// dependencies as a fallback when its symbols are absent from RTLD_NEXT.
fn next(name: &CStr, library: &CStr) -> *mut c_void {
    unsafe {
        let symbol = libc::dlsym(libc::RTLD_NEXT, name.as_ptr());
        if !symbol.is_null() {
            return symbol;
        }
        let handle = libc::dlopen(library.as_ptr(), libc::RTLD_LAZY | libc::RTLD_NOLOAD);
        if handle.is_null() {
            return null_mut();
        }
        let symbol = libc::dlsym(handle, name.as_ptr());
        libc::dlclose(handle);
        symbol
    }
}

macro_rules! function {
    ($symbol:expr, $library:expr, $ty:ty) => {{
        static SLOT: OnceLock<Option<$ty>> = OnceLock::new();
        *SLOT.get_or_init(|| {
            let ptr = next($symbol, $library);
            (!ptr.is_null()).then(|| unsafe { std::mem::transmute::<*mut c_void, $ty>(ptr) })
        })
    }};
}

mod shm;

fn fd_kind(fd: c_int) -> &'static str {
    unsafe {
        let mut status: libc::stat = std::mem::zeroed();
        if libc::fstat(fd, &mut status) != 0 {
            return "invalid";
        }
        let mode = status.st_mode & libc::S_IFMT;
        if mode == libc::S_IFSOCK {
            return "socket";
        }
        let label = std::fs::read_link(format!("/proc/self/fd/{fd}")).ok();
        if label.as_ref().is_some_and(|path| {
            let path = path.as_os_str().to_string_lossy();
            path.contains("dmabuf") || path.contains("dma-buf")
        }) || std::fs::read_to_string(format!("/proc/self/fdinfo/{fd}"))
            .is_ok_and(|info| info.lines().any(|line| line.starts_with("exp_name:")))
        {
            return "dma-buf";
        }
        match mode {
            libc::S_IFREG => "regular/shared",
            libc::S_IFCHR => "character",
            0 => "anonymous",
            _ => "other",
        }
    }
}

fn dup(fd: c_int) -> Option<OwnedFd> {
    let copied = unsafe { libc::fcntl(fd, libc::F_DUPFD_CLOEXEC, 0) };
    (copied >= 0).then(|| unsafe { OwnedFd::from_raw_fd(copied) })
}

#[derive(Clone, Copy)]
struct Geometry {
    width: u16,
    height: u16,
    depth: u8,
    bpp: u8,
}

impl Geometry {
    fn supported(self) -> bool {
        self.bpp == 32
            && matches!(self.depth, 24 | 32)
            && (1..=8192).contains(&self.width)
            && (1..=8192).contains(&self.height)
    }
}

enum Source {
    Android(AndroidBuffer),
    Shared {
        file: File,
        stride: u32,
        offset: u32,
    },
}

struct StagedImage {
    shape: Geometry,
    source: Source,
    shared: Option<shm::SharedPixmap>,
}

type Pixmaps = HashMap<(usize, u32), Arc<Mutex<StagedImage>>>;
static STAGED: Mutex<Option<Pixmaps>> = Mutex::new(None);

fn shared_source(fd: c_int, shape: Geometry, stride: u32, offset: u32) -> Option<Source> {
    let row = u64::from(shape.width) * 4;
    if u64::from(stride) < row || stride > 1024 * 1024 || fd_kind(fd) != "regular/shared" {
        return None;
    }
    let file = File::from(dup(fd)?);
    let end = u64::from(offset) + u64::from(stride) * u64::from(shape.height - 1) + row;
    if file.metadata().ok()?.len() < end || end > 512 * 1024 * 1024 {
        return None;
    }
    // Verify pread works here rather than substituting a black, unusable pixmap.
    file.read_exact_at(&mut [0], u64::from(offset)).ok()?;
    Some(Source::Shared {
        file,
        stride,
        offset,
    })
}

type CreatePixmap = unsafe extern "C" fn(Conn, u8, u32, u32, u16, u16) -> Cookie;
fn stage(
    c: Conn,
    pixmap: u32,
    drawable: u32,
    shape: Geometry,
    source: Source,
    checked: bool,
) -> Option<Cookie> {
    let create = if checked {
        function!(c"xcb_create_pixmap_checked", c"libxcb.so", CreatePixmap)?
    } else {
        function!(c"xcb_create_pixmap", c"libxcb.so", CreatePixmap)?
    };
    let mut table = STAGED.lock().ok()?;
    let entries = table.get_or_insert_with(HashMap::new);
    let key = (c as usize, pixmap);
    if entries.len() >= 64 || entries.contains_key(&key) {
        return None;
    }
    let (shared, result) = match shm::SharedPixmap::create(c, pixmap, drawable, shape) {
        Some((shared, cookie)) => {
            static NOTICE: Once = Once::new();
            NOTICE.call_once(|| log("Using MIT-SHM pixmaps for private buffers"));
            (Some(shared), cookie)
        }
        None => (None, unsafe {
            create(c, shape.depth, pixmap, drawable, shape.width, shape.height)
        }),
    };
    if result.sequence == 0 {
        return None;
    }
    entries.insert(
        key,
        Arc::new(Mutex::new(StagedImage {
            shape,
            source,
            shared,
        })),
    );
    Some(result)
}

#[repr(C)]
struct ModifierReply {
    response: u8,
    pad: u8,
    sequence: u16,
    length: u32,
    window_count: u32,
    screen_count: u32,
    tail: [u8; 16],
}

fn supports_modifier(c: Conn, drawable: u32, geometry: Geometry, modifier: u64) -> bool {
    type Request = unsafe extern "C" fn(Conn, u32, u8, u8) -> Cookie;
    type Reply = unsafe extern "C" fn(Conn, Cookie, *mut *mut c_void) -> *mut ModifierReply;
    let Some(request) = function!(
        c"xcb_dri3_get_supported_modifiers",
        c"libxcb-dri3.so",
        Request
    ) else {
        return false;
    };
    let Some(reply) = function!(
        c"xcb_dri3_get_supported_modifiers_reply",
        c"libxcb-dri3.so",
        Reply
    ) else {
        return false;
    };
    unsafe {
        let mut error = null_mut();
        let data = reply(
            c,
            request(c, drawable, geometry.depth, geometry.bpp),
            &mut error,
        );
        if !error.is_null() {
            libc::free(error);
        }
        if data.is_null() {
            return false;
        }
        let count = (*data).window_count.saturating_add((*data).screen_count);
        let matches = count <= 512
            && count.saturating_mul(2) <= (*data).length
            && std::slice::from_raw_parts(data.add(1).cast::<u64>(), count as usize)
                .contains(&modifier);
        libc::free(data.cast());
        matches
    }
}

type Plural = unsafe extern "C" fn(
    Conn,
    u32,
    u32,
    u8,
    u16,
    u16,
    u32,
    u32,
    u32,
    u32,
    u32,
    u32,
    u32,
    u32,
    u8,
    u8,
    u64,
    *const i32,
) -> Cookie;

fn translate(
    original: Plural,
    c: Conn,
    pixmap: u32,
    drawable: u32,
    shape: Geometry,
    stride: u32,
    offset: u32,
    modifier: u64,
    fd: c_int,
    checked: bool,
) -> Option<Cookie> {
    if trace_only() || !shape.supported() || !matches!(modifier, 1255 | 1274) {
        return None;
    }
    let cpu = if modifier == 1255 {
        let buffer = AndroidBuffer::receive(fd, shape.width, shape.height)?;
        if let Some(dma) = buffer.export(shape.depth) {
            if (dma.modifier != 0 || dma.stride >= u32::from(shape.width) * 4)
                && supports_modifier(c, drawable, shape, dma.modifier)
            {
                static NOTICE: Once = Once::new();
                NOTICE.call_once(|| {
                    log(format!(
                        "AHB exported through EGL as DMA-BUF with modifier 0x{:016x}",
                        dma.modifier
                    ))
                });
                let replacement = dma.fd.into_raw_fd();
                let result = unsafe {
                    original(
                        c,
                        pixmap,
                        drawable,
                        1,
                        shape.width,
                        shape.height,
                        dma.stride,
                        dma.offset,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        shape.depth,
                        shape.bpp,
                        dma.modifier,
                        &replacement,
                    )
                };
                // libxcb owns replacement; only our discarded original handle is closed here.
                drop(unsafe { OwnedFd::from_raw_fd(fd) });
                return Some(result);
            }
        }
        if !buffer.supports_gpu_rows() && !buffer.cpu_rows(|_, _| true) {
            return None;
        }
        Source::Android(buffer)
    } else {
        shared_source(fd, shape, stride, offset)?
    };
    let cookie = stage(c, pixmap, drawable, shape, cpu, checked)?;
    static NOTICE: Once = Once::new();
    NOTICE.call_once(|| log("Using standard X11 pixmaps and row uploads for a private buffer"));
    drop(unsafe { OwnedFd::from_raw_fd(fd) });
    Some(cookie)
}

macro_rules! plural_export {
    ($name:ident, $symbol:literal, $checked:literal) => {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn $name(
            c: Conn, pixmap: u32, drawable: u32, n: u8, width: u16, height: u16,
            s0: u32, o0: u32, s1: u32, o1: u32, s2: u32, o2: u32, s3: u32, o3: u32,
            depth: u8, bpp: u8, modifier: u64, fds: *const i32,
        ) -> Cookie {
            let Some(original) = function!($symbol, c"libxcb-dri3.so", Plural) else { return Cookie { sequence: 0 }; };
            let fd = if n != 0 && !fds.is_null() { unsafe { *fds } } else { -1 };
            static SEEN: AtomicU8 = AtomicU8::new(0);
            let kind = match modifier { 0 => 1, 1255 => 2, 1274 => 4, _ => 8 };
            if SEEN.fetch_or(kind, Ordering::Relaxed) & kind == 0 {
                log(format!("{} called: {width}x{height}, depth {depth}, bpp {bpp}, planes {n}, modifier 0x{modifier:016x}, fd {}",
                    stringify!($name), fd_kind(fd)));
            }
            if n == 1 && fd >= 0 && matches!(modifier, 1255 | 1274) && !trace_only() {
                let shape = Geometry { width, height, depth, bpp };
                if let Some(cookie) = translate(original, c, pixmap, drawable, shape, s0, o0, modifier, fd, $checked) {
                    return cookie;
                }
                static UNSUPPORTED: Once = Once::new();
                UNSUPPORTED.call_once(|| log("Private buffer has no validated DMA-BUF export or readable CPU fallback; leaving the native XCB error path intact"));
            }
            unsafe { original(c, pixmap, drawable, n, width, height, s0, o0, s1, o1, s2, o2, s3, o3, depth, bpp, modifier, fds) }
        }
    };
}

plural_export!(
    xcb_dri3_pixmap_from_buffers_checked,
    c"xcb_dri3_pixmap_from_buffers_checked",
    true
);
plural_export!(
    xcb_dri3_pixmap_from_buffers,
    c"xcb_dri3_pixmap_from_buffers",
    false
);

type Singular = unsafe extern "C" fn(Conn, u32, u32, u32, u16, u16, u16, u8, u8, c_int) -> Cookie;
macro_rules! singular_export {
    ($name:ident, $symbol:literal) => {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn $name(
            c: Conn,
            pixmap: u32,
            drawable: u32,
            size: u32,
            width: u16,
            height: u16,
            stride: u16,
            depth: u8,
            bpp: u8,
            fd: c_int,
        ) -> Cookie {
            static NOTICE: Once = Once::new();
            NOTICE.call_once(|| {
                log(format!(
                    "{} called: {width}x{height} stride {stride} depth {depth} bpp {bpp}, fd {}",
                    stringify!($name),
                    fd_kind(fd)
                ))
            });
            let Some(original) = function!($symbol, c"libxcb-dri3.so", Singular) else {
                return Cookie { sequence: 0 };
            };
            unsafe {
                original(
                    c, pixmap, drawable, size, width, height, stride, depth, bpp, fd,
                )
            }
        }
    };
}

singular_export!(
    xcb_dri3_pixmap_from_buffer_checked,
    c"xcb_dri3_pixmap_from_buffer_checked"
);
singular_export!(xcb_dri3_pixmap_from_buffer, c"xcb_dri3_pixmap_from_buffer");

fn await_source(c: Conn, fence: u32) -> bool {
    if fence == 0 {
        return true;
    }
    type Query = unsafe extern "C" fn(Conn, u32) -> Cookie;
    type Poll = unsafe extern "C" fn(Conn, u32, *mut *mut c_void, *mut *mut c_void) -> c_int;
    type Flush = unsafe extern "C" fn(Conn) -> c_int;
    type Discard = unsafe extern "C" fn(Conn, u32);
    let Some(query) = function!(c"xcb_sync_query_fence", c"libxcb-sync.so", Query) else {
        return false;
    };
    let Some(poll) = function!(c"xcb_poll_for_reply", c"libxcb.so", Poll) else {
        return false;
    };
    let Some(flush) = function!(c"xcb_flush", c"libxcb.so", Flush) else {
        return false;
    };
    let Some(discard) = function!(c"xcb_discard_reply", c"libxcb.so", Discard) else {
        return false;
    };
    unsafe {
        // Poll the fence state instead of enqueuing SyncAwaitFence: an untriggered
        // fence must not stall the original client's X11 request stream.
        let mut cookie = query(c, fence);
        flush(c);
        let until = std::time::Instant::now() + std::time::Duration::from_millis(300);
        while std::time::Instant::now() < until {
            let mut result = null_mut();
            let mut error = null_mut();
            if poll(c, cookie.sequence, &mut result, &mut error) != 0 {
                let valid = !result.is_null() && error.is_null();
                // xcb_sync_query_fence_reply_t.triggered follows its 8-byte header.
                let triggered = valid && *result.cast::<u8>().add(8) != 0;
                libc::free(result);
                libc::free(error);
                if !valid || triggered {
                    return triggered;
                }
                std::thread::sleep(std::time::Duration::from_millis(4));
                cookie = query(c, fence);
                flush(c);
            }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        discard(c, cookie.sequence);
        false
    }
}

fn upload(c: Conn, pixmap: u32, fence: u32) -> Option<bool> {
    let picture = STAGED
        .lock()
        .ok()?
        .as_ref()?
        .get(&(c as usize, pixmap))?
        .clone();
    let mut picture = picture.lock().ok()?;
    if !await_source(c, fence) {
        return Some(false);
    }
    let g = picture.shape;
    let StagedImage { source, shared, .. } = &mut *picture;
    if let Some(shared) = shared {
        // The WSI reacquires the image after Present releases it to the producer.
        let row = usize::from(g.width) * 4;
        let bytes = shared.bytes_mut();
        return Some(match source {
            Source::Shared {
                file,
                stride,
                offset,
            } if *stride as usize == row => file.read_exact_at(bytes, u64::from(*offset)).is_ok(),
            Source::Shared {
                file,
                stride,
                offset,
            } => bytes.chunks_exact_mut(row).enumerate().all(|(y, out)| {
                file.read_exact_at(out, u64::from(*offset) + y as u64 * u64::from(*stride))
                    .is_ok()
            }),
            Source::Android(buffer) => buffer.rows(|pixels, stride| {
                for (output, input) in bytes.chunks_exact_mut(row).zip(pixels.chunks_exact(stride))
                {
                    output.copy_from_slice(&input[..row]);
                    for pixel in output.chunks_exact_mut(4) {
                        pixel.swap(0, 2);
                    }
                }
                true
            }),
        });
    }
    type Generate = unsafe extern "C" fn(Conn) -> u32;
    type CreateGc = unsafe extern "C" fn(Conn, u32, u32, u32, *const u32) -> Cookie;
    type FreeGc = unsafe extern "C" fn(Conn, u32) -> Cookie;
    type Put = unsafe extern "C" fn(
        Conn,
        u8,
        u32,
        u32,
        u16,
        u16,
        i16,
        i16,
        u8,
        u8,
        u32,
        *const u8,
    ) -> Cookie;
    let generate = function!(c"xcb_generate_id", c"libxcb.so", Generate)?;
    let create_gc = function!(c"xcb_create_gc", c"libxcb.so", CreateGc)?;
    let free_gc = function!(c"xcb_free_gc", c"libxcb.so", FreeGc)?;
    let put = function!(c"xcb_put_image", c"libxcb.so", Put)?;
    let gc = unsafe { generate(c) };
    if gc == u32::MAX || unsafe { create_gc(c, gc, pixmap, 0, null()) }.sequence == 0 {
        return Some(false);
    }
    let row = usize::from(g.width) * 4;
    let stripes = (60 * 1024 / row).max(1);
    let mut scratch = vec![0u8; row * stripes];
    let mut write_rows = |source: &mut dyn FnMut(usize, &mut [u8]) -> bool| {
        for top in (0..usize::from(g.height)).step_by(stripes) {
            let rows = stripes.min(usize::from(g.height) - top);
            let bytes = &mut scratch[..rows * row];
            for (index, output) in bytes.chunks_exact_mut(row).enumerate() {
                if !source(top + index, output) {
                    return false;
                }
            }
            if unsafe {
                put(
                    c,
                    2,
                    pixmap,
                    gc,
                    g.width,
                    rows as u16,
                    0,
                    top as i16,
                    0,
                    g.depth,
                    bytes.len() as u32,
                    bytes.as_ptr(),
                )
            }
            .sequence
                == 0
            {
                return false;
            }
        }
        true
    };
    let success = match &mut picture.source {
        Source::Shared {
            file,
            stride,
            offset,
        } => write_rows(&mut |y, out| {
            file.read_exact_at(out, u64::from(*offset) + y as u64 * u64::from(*stride))
                .is_ok()
        }),
        Source::Android(buffer) => buffer.rows(|pixels, stride| {
            write_rows(&mut |y, out| {
                out.copy_from_slice(&pixels[y * stride..y * stride + row]);
                for pixel in out.chunks_exact_mut(4) {
                    pixel.swap(0, 2);
                }
                true
            })
        }),
    };
    unsafe { free_gc(c, gc) };
    Some(success)
}

type Present = unsafe extern "C" fn(
    Conn,
    u32,
    u32,
    u32,
    u32,
    u32,
    i16,
    i16,
    u32,
    u32,
    u32,
    u32,
    u64,
    u64,
    u64,
    u32,
    *const c_void,
) -> Cookie;
macro_rules! present_export {
    ($name:ident, $symbol:literal) => {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn $name(
            c: Conn, window: u32, pixmap: u32, serial: u32, valid: u32, update: u32,
            x: i16, y: i16, crtc: u32, wait: u32, idle: u32, options: u32,
            msc: u64, divisor: u64, remainder: u64, n: u32, notify: *const c_void,
        ) -> Cookie {
            let Some(original) = function!($symbol, c"libxcb-present.so", Present) else { return Cookie { sequence: 0 }; };
            static PRESENTS: AtomicU32 = AtomicU32::new(0);
            let count = PRESENTS.fetch_add(1, Ordering::Relaxed).wrapping_add(1);
            if count == 1 || trace_only() && matches!(count, 2 | 4 | 16 | 64 | 256 | 1024) {
                log(format!("{} called: count {count}, window 0x{window:x}, pixmap 0x{pixmap:x}, serial {serial}, wait fence 0x{wait:x}, idle fence 0x{idle:x}", stringify!($name)));
            }
            if upload(c, pixmap, wait) == Some(false) {
                static FAILED: Once = Once::new();
                FAILED.call_once(|| log("Could not refresh a staged pixmap before Present; keeping native completion and idle events"));
            }
            unsafe { original(c, window, pixmap, serial, valid, update, x, y, crtc, wait, idle, options,
                msc, divisor, remainder, n, notify) }
        }
    };
}

present_export!(xcb_present_pixmap_checked, c"xcb_present_pixmap_checked");
present_export!(xcb_present_pixmap, c"xcb_present_pixmap");

#[repr(C)]
struct ProtocolError {
    response_type: u8,
    error_code: u8,
    sequence: u16,
    resource: u32,
    minor: u16,
    major: u8,
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn xcb_request_check(c: Conn, cookie: Cookie) -> *mut c_void {
    type Check = unsafe extern "C" fn(Conn, Cookie) -> *mut c_void;
    let Some(original) = function!(c"xcb_request_check", c"libxcb.so", Check) else {
        return null_mut();
    };
    let result = unsafe { original(c, cookie) };
    if !result.is_null() {
        static ERRORS: AtomicU32 = AtomicU32::new(0);
        if ERRORS.fetch_add(1, Ordering::Relaxed) < 12 {
            let error = unsafe { &*result.cast::<ProtocolError>() };
            log(format!(
                "checked X11 request rejected: major {}, minor {}, X error {}, resource 0x{:x}",
                error.major, error.minor, error.error_code, error.resource
            ));
        }
    }
    result
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn xcb_wait_for_special_event(c: Conn, queue: *mut c_void) -> *mut c_void {
    type Wait = unsafe extern "C" fn(Conn, *mut c_void) -> *mut c_void;
    let Some(original) = function!(c"xcb_wait_for_special_event", c"libxcb.so", Wait) else {
        return null_mut();
    };
    static WAIT: Once = Once::new();
    if trace_only() {
        WAIT.call_once(|| log("Waiting for the first X11 special event"));
    }
    let result = unsafe { original(c, queue) };
    if trace_only() && !result.is_null() {
        let bytes = result.cast::<u8>();
        if unsafe { *bytes & 127 } == 35 {
            let extension = unsafe { *bytes.add(1) };
            let event = unsafe { bytes.add(8).cast::<u16>().read_unaligned() };
            static EVENTS: AtomicU8 = AtomicU8::new(0);
            let kind = match event {
                1 => 1,
                2 => 2,
                _ => 4,
            };
            if EVENTS.fetch_or(kind, Ordering::Relaxed) & kind == 0 {
                log(format!(
                    "Received X11 special event: extension {extension}, event {event}"
                ));
            }
        }
    }
    result
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn xcb_sync_await_fence(c: Conn, count: u32, ids: *const u32) -> Cookie {
    type Await = unsafe extern "C" fn(Conn, u32, *const u32) -> Cookie;
    let Some(original) = function!(c"xcb_sync_await_fence", c"libxcb-sync.so", Await) else {
        return Cookie { sequence: 0 };
    };
    static AWAITS: AtomicU32 = AtomicU32::new(0);
    if trace_only() {
        let calls = AWAITS.fetch_add(1, Ordering::Relaxed);
        if calls < 4 && count > 0 && !ids.is_null() {
            let fence = unsafe { *ids };
            log(format!(
                "X11 client queued SyncAwaitFence: first fence 0x{fence:x}, count {count}"
            ));
        }
    }
    unsafe { original(c, count, ids) }
}

type FreePixmap = unsafe extern "C" fn(Conn, u32) -> Cookie;
macro_rules! free_export {
    ($name:ident, $symbol:literal) => {
        #[unsafe(no_mangle)]
        pub unsafe extern "C" fn $name(c: Conn, pixmap: u32) -> Cookie {
            if let Ok(mut table) = STAGED.lock() {
                if let Some(entries) = table.as_mut() {
                    entries.remove(&(c as usize, pixmap));
                }
            }
            let Some(original) = function!($symbol, c"libxcb.so", FreePixmap) else {
                return Cookie { sequence: 0 };
            };
            unsafe { original(c, pixmap) }
        }
    };
}

free_export!(xcb_free_pixmap_checked, c"xcb_free_pixmap_checked");
free_export!(xcb_free_pixmap, c"xcb_free_pixmap");

#[unsafe(no_mangle)]
pub unsafe extern "C" fn xcb_disconnect(c: Conn) {
    shm::forget(c);
    if let Ok(mut table) = STAGED.lock() {
        if let Some(entries) = table.as_mut() {
            entries.retain(|(connection, _), _| *connection != c as usize);
        }
    }
    type Disconnect = unsafe extern "C" fn(Conn);
    if let Some(original) = function!(c"xcb_disconnect", c"libxcb.so", Disconnect) {
        unsafe { original(c) };
    }
}
