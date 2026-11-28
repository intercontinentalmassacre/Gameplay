/* This runs in the glibc Gamescope guest. PRoot maps filesystem sockets, while
 * Linux abstract sockets would reach the device-wide namespace. */
extern void *dlsym(void *, const char *);
extern int *__errno_location(void);

struct x11_address {
    unsigned short family;
    char path[108];
};

int connect(int fd, const void *address, unsigned int length)
{
    typedef int (*connect_fn)(int, const void *, unsigned int);
    connect_fn next = (connect_fn)dlsym((void *)-1, "connect");
    if (!next) {
        *__errno_location() = 38; /* ENOSYS */
        return -1;
    }

    const struct x11_address *original = address;
    const char prefix[] = "/tmp/.X11-unix/X";
    const unsigned int prefix_length = sizeof(prefix) - 1;
    if (!original || length <= sizeof(original->family) + 1 + prefix_length ||
        length > sizeof(*original) || original->family != 1 || original->path[0] != 0) {
        return next(fd, address, length);
    }
    for (unsigned int i = 0; i < prefix_length; ++i) {
        if (original->path[i + 1] != prefix[i]) return next(fd, address, length);
    }
    struct x11_address local;
    local.family = original->family;
    unsigned int path_length = length - sizeof(local.family) - 1;
    unsigned int i = prefix_length;
    while (i < path_length && original->path[i + 1] >= '0' && original->path[i + 1] <= '9') ++i;
    if (i == prefix_length || (i < path_length && original->path[i + 1] != 0)) {
        return next(fd, address, length);
    }
    path_length = i;
    for (i = 0; i < path_length; ++i) local.path[i] = original->path[i + 1];
    local.path[path_length] = 0;
    return next(fd, &local, sizeof(local.family) + path_length + 1);
}
