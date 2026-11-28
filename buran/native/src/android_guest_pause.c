#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <jni.h>
#include <sys/syscall.h>
typedef int ptrace_request;
#else
typedef enum __ptrace_request ptrace_request;
#endif

struct parked_tracee {
    pid_t tid;
    ptrace_request request;
    void *address;
    void *data;
    struct parked_tracee *next;
};

static long (*real_ptrace)(ptrace_request, ...);
static pid_t (*real_waitpid)(pid_t, int *, int);
static char *pause_file;
static char *parked_file;
static bool paused;
static struct parked_tracee *parked;

__attribute__((constructor))
static void configure(void)
{
    real_ptrace = dlsym(RTLD_NEXT, "ptrace");
    real_waitpid = dlsym(RTLD_NEXT, "waitpid");
    const char *path = getenv("BURAN_GUEST_PAUSE_FILE");
    if (path != NULL) {
        if (asprintf(&parked_file, "%s.parked", path) != -1)
            pause_file = strdup(path);
        unsetenv("BURAN_GUEST_PAUSE_FILE");
        unsetenv("LD_PRELOAD");
    }
}

long ptrace(ptrace_request request, ...)
{
    va_list args;
    va_start(args, request);
    pid_t tid = va_arg(args, pid_t);
    void *address = va_arg(args, void *);
    void *data = va_arg(args, void *);
    va_end(args);

    // PRoot can continue a SIGSTOP group stop. Defer its actual ptrace restart.
    if (paused && (request == PTRACE_CONT || request == PTRACE_SYSCALL ||
                   request == PTRACE_SINGLESTEP)) {
        struct parked_tracee *tracee = malloc(sizeof(*tracee));
        if (tracee != NULL) {
            *tracee = (struct parked_tracee){tid, request, address, data, parked};
            parked = tracee;
            int saved_errno = errno;
            int fd = open(parked_file, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
            if (fd >= 0) {
                char line[32];
                int length = snprintf(line, sizeof(line), "%d\n", tid);
                if (length > 0 && write(fd, line, (size_t)length) != length)
                    unlink(parked_file);
                close(fd);
            }
            errno = saved_errno;
            return 0;
        }
    }
    return real_ptrace(request, tid, address, data);
}

static void synchronize_pause(void)
{
    int saved_errno = errno;
    bool was_paused = paused;
    paused = access(pause_file, F_OK) == 0;
    while (!paused && parked != NULL) {
        struct parked_tracee *tracee = parked;
        parked = tracee->next;
        // A synthetic SIGSTOP exists only to bring the task to a ptrace stop.
        void *data = tracee->data == (void *)(long)SIGSTOP ? NULL : tracee->data;
        real_ptrace(tracee->request, tracee->tid, tracee->address, data);
        free(tracee);
    }
    if (was_paused && !paused)
        unlink(parked_file);
    errno = saved_errno;
}

pid_t waitpid(pid_t pid, int *status, int options)
{
    if (pause_file == NULL)
        return real_waitpid(pid, status, options);

    for (;;) {
        if (paused)
            synchronize_pause();
        pid_t result = real_waitpid(pid, status, options | (paused ? WNOHANG : 0));
        if (result > 0 && status != NULL && WIFSTOPPED(*status) &&
            WSTOPSIG(*status) == SIGSTOP)
            synchronize_pause();
        if (result != 0 || !paused || (options & WNOHANG))
            return result;

        // waitpid has no timeout, so poll only while the guest is paused.
        const struct timespec interval = {.tv_nsec = 10000000};
        nanosleep(&interval, NULL);
    }
}

#ifdef __ANDROID__
JNIEXPORT void JNICALL
Java_org_buran_runtime_BoxRuntime_stopTracee(JNIEnv *env, jclass clazz, jint pid, jint tid)
{
    (void)env;
    (void)clazz;
    if (pid > 0 && tid > 0)
        syscall(SYS_tgkill, pid, tid, SIGSTOP);
}
#endif
