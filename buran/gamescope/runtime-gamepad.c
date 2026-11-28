#define _GNU_SOURCE
#include <SDL2/SDL.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Gameplay WinHandler's gamepad_shm/gamepad.mem ABI, slot 0. */
struct pad_state {
    int16_t axes[6];
    uint8_t buttons[15];
    uint8_t hat;
};
struct gameplay_pad {
    _Atomic uint32_t sequence;
    struct pad_state state;
    uint8_t rumble[8];
    _Atomic uint32_t connected;
};

enum { SHM_SIZE = 64 };
_Static_assert(offsetof(struct gameplay_pad, state) == 4, "Gameplay payload");
_Static_assert(offsetof(struct gameplay_pad, connected) == 40, "Gameplay connection flag");
_Static_assert(sizeof(struct gameplay_pad) == 44, "Gameplay used bytes");

static const struct gameplay_pad *shared;
static int local_writer;
static SDL_Joystick *joystick;
static SDL_JoystickID instance = -1;

/* Resolve the same native SDL2 that Box64 wraps for Wine's winebus. */
#define SDL_FUNCTIONS(X) \
    X(SDL_WasInit) X(SDL_GetError) X(SDL_NumJoysticks) X(SDL_JoystickGetDeviceInstanceID) \
    X(SDL_JoystickAttachVirtualEx) X(SDL_JoystickDetachVirtual) X(SDL_JoystickOpen) \
    X(SDL_JoystickInstanceID) X(SDL_JoystickClose) X(SDL_JoystickSetVirtualAxis) \
    X(SDL_JoystickSetVirtualButton) X(SDL_JoystickSetVirtualHat)
#define DECLARE(function) static __typeof__(&function) p_##function;
SDL_FUNCTIONS(DECLARE)
#undef DECLARE

static void apply_state(int connected, const struct pad_state *state)
{
    if (!connected) {
        if (!joystick) return;
        p_SDL_JoystickClose(joystick);
        joystick = NULL;
        int count = p_SDL_NumJoysticks();
        for (int i = 0; i < count; ++i) {
            if (p_SDL_JoystickGetDeviceInstanceID(i) == instance) {
                p_SDL_JoystickDetachVirtual(i);
                break;
            }
        }
        instance = -1;
        return;
    }

    if (!joystick) {
        SDL_VirtualJoystickDesc description = {
            .version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION,
            .type = SDL_JOYSTICK_TYPE_GAMECONTROLLER,
            .naxes = 6, .nbuttons = 15, .nhats = 1,
            .vendor_id = 0x045e, .product_id = 0x028e,
            .button_mask = 0x7fff, .axis_mask = 0x3f,
            .name = "Xbox 360 Controller",
        };
        int index = p_SDL_JoystickAttachVirtualEx(&description);
        if (index >= 0) {
            joystick = p_SDL_JoystickOpen(index);
            if (!joystick) p_SDL_JoystickDetachVirtual(index);
        }
        if (!joystick) {
            fprintf(stderr, "Buran gameplay pad: SDL virtual joystick failed: %s\n", p_SDL_GetError());
            return;
        }
        instance = p_SDL_JoystickInstanceID(joystick);
        fprintf(stderr, "Buran gameplay pad: SDL virtual controller connected\n");
    }

    for (unsigned int i = 0; i < 6; ++i) p_SDL_JoystickSetVirtualAxis(joystick, i, state->axes[i]);
    for (unsigned int i = 0; i < 15; ++i) p_SDL_JoystickSetVirtualButton(joystick, i, state->buttons[i]);
    p_SDL_JoystickSetVirtualHat(joystick, 0, state->hat);
}

static void *update_pad(void *unused)
{
    (void)unused;
    uint32_t last = atomic_load_explicit(&shared->sequence, memory_order_acquire) - 1;
    const struct timespec timeout = {.tv_nsec = 100000000};
    for (;;) {
        uint32_t seq = atomic_load_explicit(&shared->sequence, memory_order_acquire);
        if (local_writer && (seq & 1u)) {
            syscall(SYS_futex, &shared->sequence, FUTEX_WAIT, seq, &timeout, NULL, 0);
            continue;
        }
        if (seq != last) {
            struct pad_state state = shared->state;
            int connected = atomic_load_explicit(&shared->connected, memory_order_acquire);
            atomic_thread_fence(memory_order_acquire);
            if (seq != atomic_load_explicit(&shared->sequence, memory_order_acquire)) continue;
            apply_state(connected, &state);
            last = seq;
        }
        syscall(SYS_futex, &shared->sequence, FUTEX_WAIT, last, &timeout, NULL, 0);
    }
    return NULL;
}

static void *start_pad(void *unused)
{
    (void)unused;
    const char *path = getenv("BURAN_GAMEPAD_SHM");
    if (!path || !*path) return NULL;
    local_writer = getenv("BURAN_GAMEPAD_LOCAL") != NULL;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd == -1) return NULL;
    struct stat file;
    if (fstat(fd, &file) == -1 || !S_ISREG(file.st_mode) || file.st_size != SHM_SIZE) {
        close(fd);
        return NULL;
    }
    void *mapping = mmap(NULL, SHM_SIZE, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (mapping == MAP_FAILED) return NULL;
    shared = mapping;

    // Box64's native SDL shim must be loaded and initialized by Wine first.
    void *sdl = NULL;
    for (int i = 0; i < 600 && !sdl; i++) {
        sdl = dlopen("libSDL2-2.0.so.0", RTLD_NOLOAD | RTLD_LAZY);
        if (!sdl) usleep(25000);
    }
    if (!sdl) goto fail;
#define LOAD(function) p_##function = dlsym(sdl, #function); if (!p_##function) goto fail;
    SDL_FUNCTIONS(LOAD)
#undef LOAD
    for (int i = 0; !(p_SDL_WasInit(SDL_INIT_JOYSTICK) & SDL_INIT_JOYSTICK); i++) {
        if (i == 600) goto fail;
        usleep(25000);
    }
    return update_pad(NULL);

fail:
    if (sdl) dlclose(sdl);
    munmap(mapping, SHM_SIZE);
    return NULL;
}

__attribute__((constructor)) static void create_pad_thread(void)
{
    if (!getenv("BURAN_GAMEPAD_SHM")) return;
    pthread_t thread;
    if (pthread_create(&thread, NULL, start_pad, NULL) == 0) pthread_detach(thread);
}
