use libc::{c_char, c_int, c_void};
use std::ptr::{null, null_mut};

type Handle = *mut c_void;
type GlName = u32;
type ImageTarget = unsafe extern "C" fn(u32, Handle);
type DestroyImage = unsafe extern "C" fn(Handle, Handle) -> u32;

const EGL_NONE: c_int = 0x3038;
const EGL_OPENGL_ES_API: u32 = 0x30a0;
const GL_TEXTURE_2D: u32 = 0x0de1;
const GL_TEXTURE_EXTERNAL_OES: u32 = 0x8d65;
const GL_FRAMEBUFFER: u32 = 0x8d40;
const GL_COLOR_ATTACHMENT0: u32 = 0x8ce0;
const GL_FRAMEBUFFER_COMPLETE: u32 = 0x8cd5;
const GL_RGBA: u32 = 0x1908;
const GL_UNSIGNED_BYTE: u32 = 0x1401;

#[link(name = "EGL")]
unsafe extern "C" {
    fn eglBindAPI(api: u32) -> u32;
    fn eglChooseConfig(
        display: Handle,
        attributes: *const c_int,
        config: *mut Handle,
        capacity: c_int,
        count: *mut c_int,
    ) -> u32;
    fn eglCreateContext(
        display: Handle,
        config: Handle,
        shared: Handle,
        attributes: *const c_int,
    ) -> Handle;
    fn eglCreatePbufferSurface(display: Handle, config: Handle, attributes: *const c_int)
    -> Handle;
    fn eglDestroyContext(display: Handle, context: Handle) -> u32;
    fn eglDestroySurface(display: Handle, surface: Handle) -> u32;
    fn eglGetCurrentContext() -> Handle;
    fn eglGetCurrentDisplay() -> Handle;
    fn eglGetCurrentSurface(kind: c_int) -> Handle;
    fn eglGetDisplay(native: Handle) -> Handle;
    fn eglGetProcAddress(name: *const c_char) -> Handle;
    fn eglInitialize(display: Handle, major: *mut c_int, minor: *mut c_int) -> u32;
    fn eglMakeCurrent(display: Handle, draw: Handle, read: Handle, context: Handle) -> u32;
    fn eglQueryAPI() -> u32;
}

#[link(name = "GLESv2")]
unsafe extern "C" {
    fn glActiveTexture(texture: u32);
    fn glAttachShader(program: GlName, shader: GlName);
    fn glBindFramebuffer(target: u32, name: GlName);
    fn glBindTexture(target: u32, name: GlName);
    fn glCheckFramebufferStatus(target: u32) -> u32;
    fn glCompileShader(shader: GlName);
    fn glCreateProgram() -> GlName;
    fn glCreateShader(kind: u32) -> GlName;
    fn glDisable(capability: u32);
    fn glDrawArrays(mode: u32, first: c_int, count: c_int);
    fn glEnableVertexAttribArray(index: u32);
    fn glFramebufferTexture2D(
        target: u32,
        attachment: u32,
        texture_target: u32,
        texture: GlName,
        level: c_int,
    );
    fn glGenFramebuffers(count: c_int, names: *mut GlName);
    fn glGenTextures(count: c_int, names: *mut GlName);
    fn glGetAttribLocation(program: GlName, name: *const c_char) -> c_int;
    fn glGetError() -> u32;
    fn glGetProgramiv(program: GlName, param: u32, value: *mut c_int);
    fn glGetShaderiv(shader: GlName, param: u32, value: *mut c_int);
    fn glGetUniformLocation(program: GlName, name: *const c_char) -> c_int;
    fn glLinkProgram(program: GlName);
    fn glReadPixels(
        x: c_int,
        y: c_int,
        width: c_int,
        height: c_int,
        format: u32,
        kind: u32,
        pixels: Handle,
    );
    fn glShaderSource(
        shader: GlName,
        count: c_int,
        source: *const *const c_char,
        length: *const c_int,
    );
    fn glTexImage2D(
        target: u32,
        level: c_int,
        internal: c_int,
        width: c_int,
        height: c_int,
        border: c_int,
        format: u32,
        kind: u32,
        pixels: *const c_void,
    );
    fn glTexParameteri(target: u32, param: u32, value: c_int);
    fn glUniform1i(location: c_int, value: c_int);
    fn glUseProgram(program: GlName);
    fn glVertexAttribPointer(
        index: u32,
        size: c_int,
        kind: u32,
        normalized: u8,
        stride: c_int,
        pointer: *const c_void,
    );
    fn glViewport(x: c_int, y: c_int, width: c_int, height: c_int);
}

struct Context {
    display: Handle,
    surface: Handle,
    context: Handle,
}

impl Context {
    fn create() -> Option<Self> {
        unsafe {
            let display = eglGetDisplay(null_mut());
            if display.is_null() || eglInitialize(display, null_mut(), null_mut()) == 0 {
                return None;
            }
            let _api = Api::gles()?;
            let attributes = [
                0x3033, 1, // EGL_SURFACE_TYPE, EGL_PBUFFER_BIT
                0x3040, 4, // EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT
                EGL_NONE,
            ];
            let mut config = null_mut();
            let mut count = 0;
            if eglChooseConfig(display, attributes.as_ptr(), &mut config, 1, &mut count) == 0
                || count != 1
            {
                return None;
            }
            let context =
                eglCreateContext(display, config, null_mut(), [0x3098, 2, EGL_NONE].as_ptr());
            if context.is_null() {
                return None;
            }
            let result = Self {
                display,
                context,
                surface: eglCreatePbufferSurface(
                    display,
                    config,
                    [0x3057, 1, 0x3056, 1, EGL_NONE].as_ptr(),
                ),
            };
            (!result.surface.is_null()).then_some(result)
        }
    }

    fn bind(&self) -> Option<Binding<'_>> {
        unsafe {
            let api = Api::gles()?;
            let display = eglGetCurrentDisplay();
            let context = eglGetCurrentContext();
            let draw = eglGetCurrentSurface(0x3059); // EGL_DRAW
            let read = eglGetCurrentSurface(0x305a); // EGL_READ
            if eglMakeCurrent(self.display, self.surface, self.surface, self.context) == 0 {
                return None;
            }
            Some(Binding {
                owner: self,
                display,
                context,
                draw,
                read,
                _api: api,
            })
        }
    }
}

impl Drop for Context {
    fn drop(&mut self) {
        unsafe {
            // Keep Android's default display initialized for other EGL clients.
            if !self.surface.is_null() {
                eglDestroySurface(self.display, self.surface);
            }
            eglDestroyContext(self.display, self.context);
        }
    }
}

struct Api(u32);

impl Api {
    fn gles() -> Option<Self> {
        let current = unsafe { eglQueryAPI() };
        if current != EGL_OPENGL_ES_API && unsafe { eglBindAPI(EGL_OPENGL_ES_API) } == 0 {
            return None;
        }
        Some(Self(current))
    }
}

impl Drop for Api {
    fn drop(&mut self) {
        if self.0 != EGL_OPENGL_ES_API {
            unsafe { eglBindAPI(self.0) };
        }
    }
}

struct Binding<'a> {
    owner: &'a Context,
    display: Handle,
    context: Handle,
    draw: Handle,
    read: Handle,
    _api: Api,
}

impl Drop for Binding<'_> {
    fn drop(&mut self) {
        unsafe {
            // Restore the caller's GLES binding, even when its context was surfaceless.
            // A successful replacement also releases our context for another presenter thread.
            if self.context.is_null()
                || eglMakeCurrent(self.display, self.draw, self.read, self.context) == 0
            {
                eglMakeCurrent(self.owner.display, null_mut(), null_mut(), null_mut());
            }
        }
    }
}

struct Image {
    display: Handle,
    handle: Handle,
    destroy: DestroyImage,
}

impl Drop for Image {
    fn drop(&mut self) {
        unsafe { (self.destroy)(self.display, self.handle) };
    }
}

pub(crate) struct Readback {
    // The EGLImage is destroyed before its owning context.
    _image: Image,
    context: Context,
    width: c_int,
    height: c_int,
    pixels: Vec<u8>,
    blit: bool,
}

impl Readback {
    // The private context is bound only within a read, under the staged pixmap lock.
    // Initialization reads the first frame and selects a path for subsequent frames.
    pub(crate) fn create(buffer: Handle, width: u32, height: u32) -> Option<Self> {
        if width == 0 || height == 0 || width > 16_384 || height > 16_384 {
            return None;
        }
        let length = (width as usize)
            .checked_mul(height as usize)?
            .checked_mul(4)?;
        if length > 256 * 1024 * 1024 {
            return None;
        }
        let mut pixels = Vec::new();
        pixels.try_reserve_exact(length).ok()?;
        pixels.resize(length, 0);

        macro_rules! proc {
            ($name:expr, $ty:ty) => {{
                let pointer = unsafe { eglGetProcAddress($name.as_ptr()) };
                if pointer.is_null() {
                    return None;
                }
                unsafe { std::mem::transmute::<Handle, $ty>(pointer) }
            }};
        }

        let context = Context::create()?;
        let binding = context.bind()?;
        let native = proc!(
            c"eglGetNativeClientBufferANDROID",
            unsafe extern "C" fn(Handle) -> Handle
        );
        let create = proc!(
            c"eglCreateImageKHR",
            unsafe extern "C" fn(Handle, Handle, u32, Handle, *const c_int) -> Handle
        );
        let destroy = proc!(c"eglDestroyImageKHR", DestroyImage);
        let target = proc!(c"glEGLImageTargetTexture2DOES", ImageTarget);
        let client = unsafe { native(buffer) };
        if client.is_null() {
            return None;
        }

        // EGL_IMAGE_PRESERVED_KHR is required when reading content produced by Vulkan.
        let handle = unsafe {
            create(
                context.display,
                null_mut(),
                0x3140,
                client,
                [0x30d2, 1, EGL_NONE].as_ptr(),
            )
        };
        if handle.is_null() {
            return None;
        }
        let image = Image {
            display: context.display,
            handle,
            destroy,
        };

        let width = width as c_int;
        let height = height as c_int;
        let blit = unsafe {
            let mut framebuffer = 0;
            glGenFramebuffers(1, &mut framebuffer);
            if framebuffer == 0 {
                return None;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            let texture = import_texture(target, GL_TEXTURE_2D, image.handle);
            if let Some(texture) = texture {
                attach_texture(texture);
            }
            let direct = texture.is_some()
                && glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
                && read_pixels(width, height, &mut pixels);

            if !direct {
                // Some EGL drivers expose Android images as external textures only.
                let sampled = texture.is_some_and(|texture| {
                    setup_blit(texture, false, width, height)
                        && blit_pixels(width, height, &mut pixels)
                });
                if !sampled {
                    let external = import_texture(target, GL_TEXTURE_EXTERNAL_OES, image.handle)?;
                    if !setup_blit(external, true, width, height)
                        || !blit_pixels(width, height, &mut pixels)
                    {
                        return None;
                    }
                }
            }
            !direct
        };
        drop(binding);
        Some(Self {
            _image: image,
            context,
            pixels,
            width,
            height,
            blit,
        })
    }

    pub(crate) fn pixels(&self) -> &[u8] {
        &self.pixels
    }

    pub(crate) fn refresh(&mut self) -> bool {
        let Some(_binding) = self.context.bind() else {
            return false;
        };
        unsafe {
            if self.blit {
                return blit_pixels(self.width, self.height, &mut self.pixels);
            }
            read_pixels(self.width, self.height, &mut self.pixels)
        }
    }
}

unsafe fn import_texture(target: ImageTarget, binding: u32, image: Handle) -> Option<GlName> {
    unsafe {
        // Discard errors from the preceding import/draw attempt in our private context.
        for _ in 0..8 {
            if glGetError() == 0 {
                break;
            }
        }
        let mut texture = 0;
        glGenTextures(1, &mut texture);
        glBindTexture(binding, texture);
        target(binding, image);
        (texture != 0 && glGetError() == 0).then_some(texture)
    }
}

unsafe fn attach_texture(texture: GlName) {
    unsafe {
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            texture,
            0,
        )
    };
}

unsafe fn read_pixels(width: c_int, height: c_int, pixels: &mut [u8]) -> bool {
    unsafe {
        glReadPixels(
            0,
            0,
            width,
            height,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pixels.as_mut_ptr().cast(),
        );
        glGetError() == 0
    }
}

unsafe fn blit_pixels(width: c_int, height: c_int, pixels: &mut [u8]) -> bool {
    unsafe {
        glDrawArrays(0x0005, 0, 4); // GL_TRIANGLE_STRIP
        read_pixels(width, height, pixels)
    }
}

unsafe fn setup_blit(texture: GlName, external: bool, width: c_int, height: c_int) -> bool {
    unsafe {
        let mut output = 0;
        glGenTextures(1, &mut output);
        glBindTexture(GL_TEXTURE_2D, output);
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA as c_int,
            width,
            height,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            null(),
        );
        attach_texture(output);
        if glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE {
            return false;
        }

        let vertex = c"attribute vec2 position; varying vec2 uv;
            void main() { uv = position * 0.5 + 0.5; gl_Position = vec4(position, 0.0, 1.0); }";
        let fragment = if external {
            c"#extension GL_OES_EGL_image_external : require
              precision highp float; varying vec2 uv; uniform samplerExternalOES frame;
              void main() { gl_FragColor = texture2D(frame, uv); }"
        } else {
            c"precision highp float; varying vec2 uv; uniform sampler2D frame;
              void main() { gl_FragColor = texture2D(frame, uv); }"
        };
        let program = glCreateProgram();
        for (kind, source) in [(0x8b31, vertex), (0x8b30, fragment)] {
            let shader = glCreateShader(kind);
            glShaderSource(shader, 1, &source.as_ptr(), null());
            glCompileShader(shader);
            let mut compiled = 0;
            glGetShaderiv(shader, 0x8b81, &mut compiled);
            if compiled == 0 {
                return false;
            }
            glAttachShader(program, shader);
        }
        glLinkProgram(program);
        let mut linked = 0;
        glGetProgramiv(program, 0x8b82, &mut linked);
        if linked == 0 {
            return false;
        }

        let position = glGetAttribLocation(program, c"position".as_ptr());
        let frame = glGetUniformLocation(program, c"frame".as_ptr());
        if position < 0 || frame < 0 {
            return false;
        }

        let binding = if external {
            GL_TEXTURE_EXTERNAL_OES
        } else {
            GL_TEXTURE_2D
        };
        glUseProgram(program);
        glActiveTexture(0x84c0); // GL_TEXTURE0
        glBindTexture(binding, texture);
        glUniform1i(frame, 0);
        for (param, value) in [
            (0x2800, 0x2600),
            (0x2801, 0x2600),
            (0x2802, 0x812f),
            (0x2803, 0x812f),
        ] {
            glTexParameteri(binding, param, value);
        }

        static VERTICES: [f32; 8] = [-1., -1., 1., -1., -1., 1., 1., 1.];
        glVertexAttribPointer(position as u32, 2, 0x1406, 0, 0, VERTICES.as_ptr().cast());
        glEnableVertexAttribArray(position as u32);
        glDisable(0x0bd0); // GL_DITHER
        glViewport(0, 0, width, height);
        glGetError() == 0
    }
}
