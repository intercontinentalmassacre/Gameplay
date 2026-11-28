use libc::{c_char, c_int, c_void};
use std::ffi::CStr;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::ptr::{NonNull, null, null_mut};
use std::slice;

#[repr(C)]
#[derive(Default)]
struct Description {
    width: u32,
    height: u32,
    layers: u32,
    format: u32,
    usage: u64,
    stride: u32,
    reserved: u32,
    reserved2: u64,
}

#[link(name = "android")]
unsafe extern "C" {
    fn AHardwareBuffer_recvHandleFromUnixSocket(socket: c_int, buffer: *mut *mut c_void) -> c_int;
    fn AHardwareBuffer_describe(buffer: *const c_void, description: *mut Description);
    fn AHardwareBuffer_release(buffer: *mut c_void);
    fn AHardwareBuffer_lock(
        buffer: *mut c_void,
        usage: u64,
        fence: c_int,
        rect: *const c_void,
        data: *mut *mut c_void,
    ) -> c_int;
    fn AHardwareBuffer_unlock(buffer: *mut c_void, fence: *mut c_int) -> c_int;
}

pub struct AndroidBuffer {
    handle: NonNull<c_void>,
    description: Description,
    readback: Option<crate::readback::Readback>,
}

// The buffer is reference-counted. Access is serialized by the pixmap lock, and
// the private EGL context is released from its thread before the lock is dropped.
unsafe impl Send for AndroidBuffer {}

impl Drop for AndroidBuffer {
    fn drop(&mut self) {
        drop(self.readback.take());
        unsafe { AHardwareBuffer_release(self.handle.as_ptr()) };
    }
}

impl AndroidBuffer {
    pub fn receive(fd: c_int, width: u16, height: u16) -> Option<Self> {
        let mut handle = null_mut();
        let mut socket: libc::stat = unsafe { std::mem::zeroed() };
        if unsafe { libc::fstat(fd, &mut socket) } != 0
            || socket.st_mode & libc::S_IFMT != libc::S_IFSOCK
        {
            return None;
        }
        // The producer sends the handle before handing the socket to XCB. Avoid
        // blocking indefinitely on a socket that does not carry an AHB message.
        let mut poll = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        if unsafe { libc::poll(&mut poll, 1, 0) } != 1 || poll.revents & libc::POLLIN == 0 {
            return None;
        }
        // The producer reads this acknowledgment after submitting PixmapFromBuffers.
        let ready = 1_u8;
        if unsafe {
            libc::send(
                fd,
                (&ready as *const u8).cast(),
                1,
                libc::MSG_DONTWAIT | libc::MSG_NOSIGNAL,
            )
        } != 1
            || unsafe { AHardwareBuffer_recvHandleFromUnixSocket(fd, &mut handle) } != 0
        {
            return None;
        }
        let mut buffer = Self {
            handle: NonNull::new(handle)?,
            description: Description::default(),
            readback: None,
        };
        unsafe { AHardwareBuffer_describe(handle, &mut buffer.description) };
        let desc = &buffer.description;
        (desc.width == u32::from(width) && desc.height == u32::from(height) && desc.layers == 1)
            .then_some(buffer)
    }

    pub fn cpu_rows(&self, f: impl FnOnce(&[u8], usize) -> bool) -> bool {
        let d = &self.description;
        // Android RGBA/RGBX8888. Convert to X11's little-endian BGRX/ARGB layout.
        if !matches!(d.format, 1 | 2)
            || d.stride < d.width
            || d.stride > 16_384
            || d.usage & 0xf == 0
        {
            return false;
        }
        let stride = d.stride as usize * 4;
        let length = stride * d.height as usize;
        let mut pixels = null_mut();
        if unsafe { AHardwareBuffer_lock(self.handle.as_ptr(), 2, -1, null(), &mut pixels) } != 0 {
            return false;
        }
        struct Unlock(*mut c_void);
        impl Drop for Unlock {
            fn drop(&mut self) {
                unsafe { AHardwareBuffer_unlock(self.0, null_mut()) };
            }
        }
        let _unlock = Unlock(self.handle.as_ptr());
        NonNull::new(pixels).is_some_and(|p| {
            f(
                unsafe { slice::from_raw_parts(p.as_ptr().cast::<u8>(), length) },
                stride,
            )
        })
    }

    pub fn export(&self, depth: u8) -> Option<DmaBuf> {
        unsafe { export_android_buffer(self.handle.as_ptr(), depth) }
    }

    pub fn supports_gpu_rows(&self) -> bool {
        let d = &self.description;
        // Android RGBA, RGBX, or BGRA8888 with GPU sampled-image or color-output usage.
        matches!(d.format, 1 | 2 | 5) && d.usage & (0x100 | 0x200) != 0
    }

    pub fn rows(&mut self, f: impl FnOnce(&[u8], usize) -> bool) -> bool {
        let d = &self.description;
        let stride = d.width as usize * 4;
        if let Some(readback) = &mut self.readback {
            if readback.refresh() {
                return f(readback.pixels(), stride);
            }
            self.readback = None;
        }
        let mut write = Some(f);
        if self.cpu_rows(|pixels, stride| write.take().unwrap()(pixels, stride)) {
            return true;
        }
        let Some(write) = write.filter(|_| self.supports_gpu_rows()) else {
            return false;
        };
        let d = &self.description;
        let Some(readback) =
            crate::readback::Readback::create(self.handle.as_ptr(), d.width, d.height)
        else {
            return false;
        };
        static NOTICE: std::sync::Once = std::sync::Once::new();
        NOTICE.call_once(|| {
            crate::log("Using EGL/GLES readback for a private Android hardware buffer")
        });
        let success = write(readback.pixels(), stride);
        self.readback = Some(readback);
        success
    }
}

pub struct DmaBuf {
    pub fd: OwnedFd,
    pub stride: u32,
    pub offset: u32,
    pub modifier: u64,
}

#[link(name = "EGL")]
unsafe extern "C" {
    fn eglGetDisplay(native: *mut c_void) -> *mut c_void;
    fn eglInitialize(display: *mut c_void, major: *mut c_int, minor: *mut c_int) -> u32;
    fn eglQueryString(display: *mut c_void, name: c_int) -> *const c_char;
    fn eglGetProcAddress(name: *const c_char) -> *mut c_void;
}

// Use only the Android/EGL-provided DMA-BUF descriptor. The private gralloc
// native_handle layout, including its fd order and modifier, is not a public ABI.
unsafe fn export_android_buffer(buffer: *mut c_void, depth: u8) -> Option<DmaBuf> {
    let display = unsafe { eglGetDisplay(null_mut()) };
    if display.is_null() || unsafe { eglInitialize(display, null_mut(), null_mut()) } == 0 {
        return None;
    }
    let extensions = unsafe { eglQueryString(display, 0x3055) }; // EGL_EXTENSIONS
    if extensions.is_null()
        || !unsafe { CStr::from_ptr(extensions) }
            .to_bytes()
            .split(|b| *b == b' ')
            .any(|name| name == b"EGL_MESA_image_dma_buf_export")
    {
        return None;
    }
    macro_rules! proc {
        ($name:expr, $ty:ty) => {{
            let pointer = unsafe { eglGetProcAddress($name.as_ptr()) };
            if pointer.is_null() {
                return None;
            }
            unsafe { std::mem::transmute::<*mut c_void, $ty>(pointer) }
        }};
    }
    type GetNative = unsafe extern "C" fn(*const c_void) -> *mut c_void;
    type Create = unsafe extern "C" fn(
        *mut c_void,
        *mut c_void,
        u32,
        *mut c_void,
        *const c_int,
    ) -> *mut c_void;
    type Destroy = unsafe extern "C" fn(*mut c_void, *mut c_void) -> u32;
    type Query =
        unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_int, *mut c_int, *mut u64) -> u32;
    type Export =
        unsafe extern "C" fn(*mut c_void, *mut c_void, *mut c_int, *mut c_int, *mut c_int) -> u32;
    let native = proc!(c"eglGetNativeClientBufferANDROID", GetNative);
    let create = proc!(c"eglCreateImageKHR", Create);
    let destroy = proc!(c"eglDestroyImageKHR", Destroy);
    let query = proc!(c"eglExportDMABUFImageQueryMESA", Query);
    let export = proc!(c"eglExportDMABUFImageMESA", Export);
    let client = unsafe { native(buffer) };
    if client.is_null() {
        return None;
    }
    let image = unsafe { create(display, null_mut(), 0x3140, client, [0x3038].as_ptr()) }; // EGL_NATIVE_BUFFER_ANDROID
    if image.is_null() {
        return None;
    }
    let mut fourcc = 0;
    let mut planes = 0;
    let mut modifier = u64::MAX;
    let mut fds = [-1; 4];
    let mut strides = [0; 4];
    let mut offsets = [-1; 4];
    let queried = unsafe { query(display, image, &mut fourcc, &mut planes, &mut modifier) } != 0;
    let compatible = compatible_format(fourcc as u32, depth);
    let exported = queried
        && planes == 1
        && compatible
        && modifier != u64::MAX
        && unsafe {
            export(
                display,
                image,
                fds.as_mut_ptr(),
                strides.as_mut_ptr(),
                offsets.as_mut_ptr(),
            )
        } != 0;
    unsafe { destroy(display, image) };
    let mut descriptors = fds.map(|fd| (fd >= 0).then(|| unsafe { OwnedFd::from_raw_fd(fd) }));
    let fd = descriptors[0].take()?;
    if !exported || strides[0] <= 0 || offsets[0] < 0 {
        return None;
    }
    unsafe { libc::fcntl(fd.as_raw_fd(), libc::F_SETFD, libc::FD_CLOEXEC) };
    Some(DmaBuf {
        fd,
        stride: strides[0] as u32,
        offset: offsets[0] as u32,
        modifier,
    })
}

fn compatible_format(fourcc: u32, depth: u8) -> bool {
    match depth {
        24 => matches!(fourcc.to_le_bytes(), [b'X' | b'A', b'R', b'2', b'4']),
        32 => fourcc.to_le_bytes() == *b"AR24",
        _ => false,
    }
}
