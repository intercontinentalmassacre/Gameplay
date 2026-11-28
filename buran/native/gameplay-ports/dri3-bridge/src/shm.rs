use super::*;

pub(super) struct SharedPixmap {
    addr: std::ptr::NonNull<u8>,
    size: usize,
}

// The mapping is only accessed under the owning staged pixmap's mutex.
unsafe impl Send for SharedPixmap {}

impl Drop for SharedPixmap {
    fn drop(&mut self) {
        unsafe { libc::munmap(self.addr.as_ptr().cast(), self.size) };
    }
}

type Query = unsafe extern "C" fn(Conn) -> Cookie;
type Reply = unsafe extern "C" fn(Conn, Cookie, *mut *mut c_void) -> *mut u8;
type Setup = unsafe extern "C" fn(Conn) -> *const u8;
type Extension = unsafe extern "C" fn(Conn, *mut c_void) -> *const u8;
type Check = unsafe extern "C" fn(Conn, Cookie) -> *mut c_void;
type Generate = unsafe extern "C" fn(Conn) -> u32;
type Attach = unsafe extern "C" fn(Conn, u32, c_int, u8) -> Cookie;
type Create = unsafe extern "C" fn(Conn, u32, u32, u16, u16, u8, u32, u32) -> Cookie;
type Detach = unsafe extern "C" fn(Conn, u32) -> Cookie;

static FORMATS: Mutex<Option<HashMap<usize, u8>>> = Mutex::new(None);

pub(super) fn forget(c: Conn) {
    if let Ok(mut cache) = FORMATS.lock() {
        if let Some(cache) = cache.as_mut() {
            cache.remove(&(c as usize));
        }
    }
}

fn formats(c: Conn) -> u8 {
    let Some(request) = function!(c"xcb_shm_query_version", c"libxcb-shm.so", Query) else {
        return 0;
    };
    let Some(reply) = function!(c"xcb_shm_query_version_reply", c"libxcb-shm.so", Reply) else {
        return 0;
    };
    let Some(setup) = function!(c"xcb_get_setup", c"libxcb.so", Setup) else {
        return 0;
    };
    let Some(extension) = function!(c"xcb_get_extension_data", c"libxcb.so", Extension) else {
        return 0;
    };
    unsafe {
        let shm_id = next(c"xcb_shm_id", c"libxcb-shm.so");
        if shm_id.is_null() {
            return 0;
        }
        let extension = extension(c, shm_id);
        if extension.is_null() || *extension.add(8) == 0 {
            return 0;
        }
        let data = setup(c);
        // Shared storage uses the server's native pixmap byte order and scanline padding.
        if data.is_null() || *data.add(30) != 0 {
            return 0;
        }
        let version = reply(c, request(c), null_mut());
        if version.is_null() {
            return 0;
        }
        let supported = *version.add(1) != 0
            && u16::from_ne_bytes([*version.add(8), *version.add(9)]) == 1
            && u16::from_ne_bytes([*version.add(10), *version.add(11)]) >= 2
            && *version.add(16) == 2; // ZPixmap
        libc::free(version.cast());
        if !supported {
            return 0;
        }
        let vendor_size = usize::from(u16::from_ne_bytes([*data.add(24), *data.add(25)]));
        let count = usize::from(*data.add(29));
        let format = data.add(40 + (vendor_size + 3) / 4 * 4);
        std::slice::from_raw_parts(format, count * 8)
            .chunks_exact(8)
            .filter(|row| row[1] == 32 && matches!(row[2], 8 | 16 | 32))
            .fold(0, |mask, row| match row[0] {
                24 => mask | 1,
                32 => mask | 2,
                _ => mask,
            })
    }
}

impl SharedPixmap {
    pub(super) fn create(
        c: Conn,
        pixmap: u32,
        drawable: u32,
        shape: Geometry,
    ) -> Option<(Self, Cookie)> {
        let mask = *FORMATS
            .lock()
            .ok()?
            .get_or_insert_with(HashMap::new)
            .entry(c as usize)
            .or_insert_with(|| formats(c));
        if mask & if shape.depth == 24 { 1 } else { 2 } == 0 {
            return None;
        }
        let generate = function!(c"xcb_generate_id", c"libxcb.so", Generate)?;
        let check = function!(c"xcb_request_check", c"libxcb.so", Check)?;
        let noop = function!(c"xcb_no_operation_checked", c"libxcb.so", Query)?;
        let attach = function!(c"xcb_shm_attach_fd_checked", c"libxcb-shm.so", Attach)?;
        let create = function!(c"xcb_shm_create_pixmap_checked", c"libxcb-shm.so", Create)?;
        let detach = function!(c"xcb_shm_detach", c"libxcb-shm.so", Detach)?;
        let size = usize::from(shape.width) * usize::from(shape.height) * 4;
        unsafe {
            let fd = libc::syscall(
                libc::SYS_memfd_create,
                c"buran-x11-pixmap".as_ptr(),
                libc::MFD_CLOEXEC,
            );
            if fd < 0 {
                return None;
            }
            let fd = OwnedFd::from_raw_fd(fd as c_int);
            use std::os::fd::AsRawFd;
            if libc::ftruncate(fd.as_raw_fd(), size as libc::off_t) != 0 {
                return None;
            }
            let addr = libc::mmap(
                null_mut(),
                size,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                fd.as_raw_fd(),
                0,
            );
            if addr == libc::MAP_FAILED {
                return None;
            }
            let mapping = Self {
                addr: std::ptr::NonNull::new(addr.cast())?,
                size,
            };
            let seg = generate(c);
            if seg == u32::MAX {
                return None;
            }
            let valid = |cookie: Cookie| {
                if cookie.sequence == 0 {
                    return false;
                }
                let error = check(c, cookie);
                libc::free(error);
                error.is_null()
            };
            // XCB owns the fd after attach; the local mmap owns an independent reference.
            if !valid(attach(c, seg, fd.into_raw_fd(), 0)) {
                return None;
            }
            let accepted = valid(create(
                c,
                pixmap,
                drawable,
                shape.width,
                shape.height,
                shape.depth,
                seg,
                0,
            ));
            // A successfully created pixmap holds the segment independently of the SHM XID.
            detach(c, seg);
            accepted.then(|| (mapping, noop(c)))
        }
    }

    pub(super) fn bytes_mut(&mut self) -> &mut [u8] {
        unsafe { std::slice::from_raw_parts_mut(self.addr.as_ptr(), self.size) }
    }
}
