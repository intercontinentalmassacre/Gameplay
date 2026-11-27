/* SPDX-License-Identifier: GPL-3.0-or-later
 * Based on GameNative/gamenative-drm c77095f22ecba46202739127f9dd7df2dde5ac26.
 * Gameplay filters console and shell windows during automatic activation.
 */
/*
 * gn-activate.c â€” make the guest game's window the foreground window, from
 * inside the wine prefix, at the moment the window exists.
 *
 * Why this exists: under gamescope the game runs as a nested child of
 * `explorer /desktop=shell` and its X window is override-redirect (measured:
 * 0x02400001, OR=1, child of the explorer desktop window). Wine does not
 * activate an override-redirect child, so the title comes up inactive and its
 * input loop ignores every device â€” the user sees "press START" that never
 * takes. A mouse click works only because wine's own click-to-focus activates
 * the window on the first press.
 *
 * Activation cannot be delivered from the Linux side. WM_ACTIVATE/WM_SETFOCUS
 * are Win32 messages and only a process in the prefix can raise them; the
 * X-level substitute was measured and fails (XSetInputFocus on the game's
 * window sticks and changes nothing, because wine received the focus change on
 * its own child and did not activate it). That is why gamescope's old A48
 * patch had to reach winhandler.exe over UDP â€” the hop into wine is
 * unavoidable. What is avoidable is everything around the hop: this program
 * needs no compositor patch, no UDP and no IPC, because it waits for the
 * window itself.
 *
 * Usage:
 *   gn-activate.exe nfs13.exe
 *   gn-activate.exe --timeout-ms 180000 --verbose NFS13.exe
 *   gn-activate.exe --list                        # every visible window
 *
 *   --watch            keep polling for the whole session and activate each
 *                      eligible window once, when it first appears. This is the
 *                      shipping shape: the session starts a launcher, and the
 *                      game's window exists only minutes later.
 *   --watch-ms N       poll interval in watch mode (default 400)
 *   --idle-ms N        exit after this long with no window left to activate
 *                      (default 60000); 0 disables the idle exit
 *   --timeout-ms N     how long to wait for the window (default 120000)
 *   --settle-ms N      wait after each activation attempt before reading the
 *                      foreground back (default 300)
 *   --force-messages   if SetForegroundWindow is refused, fall back to
 *                      SendMessage WM_ACTIVATE/WM_SETFOCUS. Off by default:
 *                      that only convinces the *game* it is active while
 *                      wine's input routing stays where it was, which produces
 *                      a title that accepts mouse but not keys.
 *   --desktop NAME     the wine desktop to join before looking (default
 *                      "shell", which is what `explorer /desktop=shell` names).
 *                      Without this the process sits on wine's default desktop
 *                      and EnumWindows returns nothing, because windows belong
 *                      to a desktop and the game's belong to explorer's.
 *   --query-fg         print wine's foreground window and exit, changing
 *                      nothing. The only read-only way to ask whether wine
 *                      considers a window active: X input focus is not that
 *                      answer (measured), and any activation call would mask
 *                      what we are trying to observe.
 *   --list             print the visible windows and exit (no activation)
 *   --verbose          per-attempt detail
 *
 *   --desktop none     stay on whatever desktop is current
 *
 * With no image argument the target is chosen by rule, because at session start
 * only the launcher is known:
 *
 *   the largest visible window that is not wine's desktop window (class
 *   "#32769", DESKTOP_CLASS_ATOM), not ours, and not already activated.
 *
 * This works without an executable name. A title's own window is the
 * largest thing on the desktop; the desktop window itself is excluded by its
 * class, which is exact rather than a guess.
 *
 * Watch mode ends on its own two ways: the desktop disappears (session over), or
 * nothing has been left to activate for --idle-ms. The second matters because a
 * launcher session outlives its game -- `winhandler.exe "steam.exe"` keeps the
 * desktop and Steam alive after the title exits -- so without it the helper would
 * poll for the rest of the session doing nothing.
 *
 * Exit codes: 0 activated (or already foreground), 1 refused, 2 no window in
 * time, 3 bad usage.
 *
 * Window selection: the target is matched by owning process image, never by
 * WM_CLASS string, so no class deny-list and no tabtip/explorer special cases.
 * Children are enumerated too â€” the game's window is a child of the desktop,
 * so EnumWindows alone (top level only) never sees it. Among matches the
 * largest visible window wins, which skips the unmapped 1x1 leftover window a
 * D3D9 title also creates.
 */

#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#define MAX_TARGETS 8
#define MAX_SEEN 128
#define POLL_MS 100
#define DESKTOP_WAIT_MS 180000

/* wine's desktop window class: DESKTOP_CLASS_ATOM in programs/explorer/desktop.c */
#define DESKTOP_CLASS "#32769"

static int g_verbose = 0;
static int g_any = 0;         /* no image argument: match by rule */
static DWORD g_own_pid;

struct target {
    char image[64]; /* lower-cased basename, e.g. "nfs13.exe" */
};

struct target g_targets[MAX_TARGETS];
static int g_ntargets = 0;

struct candidate {
    HWND hwnd;
    RECT rect;
    DWORD pid;
    char image[64];
    char cls[128];
    char title[160];
};

static void lower(char *s)
{
    for (; *s; s++) *s = (char)tolower((unsigned char)*s);
}

static const char *basename_(const char *path)
{
    const char *a = strrchr(path, '\\');
    const char *b = strrchr(path, '/');
    const char *p = a > b ? a : b;
    return p ? p + 1 : path;
}

/* Image basename of the process owning hwnd, lower-cased. Empty on failure. */
static void image_of(HWND hwnd, DWORD *pid_out, char *image, size_t image_len)
{
    DWORD pid = 0;
    HANDLE proc;
    char path[MAX_PATH];
    DWORD len = MAX_PATH;

    image[0] = '\0';
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid_out) *pid_out = pid;

    /* PROCESS_QUERY_LIMITED_INFORMATION is enough for the image name and does
     * not need the extra rights PROCESS_QUERY_INFORMATION asks for. */
    proc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!proc) return;
    if (QueryFullProcessImageNameA(proc, 0, path, &len)) {
        /* the basename of a MAX_PATH path can exceed image_len; bound it here
         * rather than trusting snprintf's silent truncation */
        snprintf(image, image_len, "%.*s", (int)image_len - 1, basename_(path));
        lower(image);
    }
    CloseHandle(proc);
}

static int matches_target(const char *image)
{
    if (g_any) return 1;
    for (int i = 0; i < g_ntargets; i++)
        if (image[0] && strcmp(image, g_targets[i].image) == 0) return 1;
    return 0;
}

static void describe(HWND hwnd, struct candidate *c)
{
    memset(c, 0, sizeof(*c));
    c->hwnd = hwnd;
    GetWindowRect(hwnd, &c->rect);
    image_of(hwnd, &c->pid, c->image, sizeof(c->image));
    GetClassNameA(hwnd, c->cls, sizeof(c->cls));
    GetWindowTextA(hwnd, c->title, sizeof(c->title));
}

static long area(const RECT *r)
{
    long w = r->right - r->left, h = r->bottom - r->top;
    return (w > 0 && h > 0) ? w * h : 0;
}

/* wine's desktop window is identifiable exactly, by class, so excluding it is
 * not a guess about sizes or titles. */
static int is_desktop_window(HWND hwnd)
{
    char cls[64];
    cls[0] = '\0';
    GetClassNameA(hwnd, cls, sizeof(cls));
    return strcmp(cls, DESKTOP_CLASS) == 0;
}

/* An eligible window: visible, real area, not the desktop, not our own. */
static int eligible(const struct candidate *c)
{
    if (!c->hwnd || area(&c->rect) <= 0) return 0;
    if (c->pid == g_own_pid) return 0;
    if (!matches_target(c->image)) return 0;
    /* The same EXE can own both a console and a game window. Also exclude
     * child controls of console hosts and the Wine taskbar. */
    if (!strcmp(c->image, "conhost.exe") || !strcmp(c->image, "wineconsole.exe")) return 0;
    for (HWND w = c->hwnd; w; w = GetAncestor(w, GA_PARENT)) {
        char cls[128] = {0};
        GetClassNameA(w, cls, sizeof(cls));
        if (!lstrcmpiA(cls, "ConsoleWindowClass") || !lstrcmpiA(cls, "WineConsoleClass") ||
            !lstrcmpiA(cls, "Shell_TrayWnd") || !lstrcmpiA(cls, "Shell_SecondaryTrayWnd"))
            return 0;
        if (!strcmp(cls, DESKTOP_CLASS)) break;
    }
    return !is_desktop_window(c->hwnd);
}

/* Windows already activated. In watch mode a window is acted on once: a refused
 * activation must not be retried every poll, or the watcher becomes a focus
 * fight with whatever the user is doing. */
static HWND g_seen[MAX_SEEN];
static int g_nseen = 0;

static int already_seen(HWND hwnd)
{
    for (int i = 0; i < g_nseen; i++)
        if (g_seen[i] == hwnd) return 1;
    return 0;
}

static void mark_seen(HWND hwnd)
{
    if (g_nseen < MAX_SEEN) g_seen[g_nseen++] = hwnd;
}

struct scan {
    struct candidate best;
    int found;
    int list;
    int skip_seen;
};

static BOOL CALLBACK scan_proc(HWND hwnd, LPARAM lp)
{
    struct scan *s = (struct scan *)lp;
    struct candidate c;

    if (!IsWindowVisible(hwnd)) return TRUE;
    describe(hwnd, &c);

    if (s->list) {
        if (area(&c.rect) > 0)
            printf("  0x%08lx %-20s pid=%-6lu %4ldx%-4ld class=%-20s %s\n",
                   (unsigned long)(ULONG_PTR)hwnd, c.image, (unsigned long)c.pid,
                   c.rect.right - c.rect.left, c.rect.bottom - c.rect.top,
                   c.cls, c.title);
        return TRUE;
    }

    /* The game's window is a child of the desktop window, so a top-level match
     * is a bonus, not the common case; scan children on every top-level too. */
    if (eligible(&c) && !(s->skip_seen && already_seen(hwnd))) {
        if (!s->found || area(&c.rect) > area(&s->best.rect)) {
            s->best = c;
            s->found = 1;
        }
    }
    return TRUE;
}

static BOOL CALLBACK child_scan_proc(HWND hwnd, LPARAM lp)
{
    struct scan *s = (struct scan *)lp;
    struct candidate c;

    if (!IsWindowVisible(hwnd)) return TRUE;
    describe(hwnd, &c);

    if (s->list) {
        if (area(&c.rect) > 0)
            printf("    child 0x%08lx %-16s pid=%-6lu %4ldx%-4ld class=%-16s %s\n",
                   (unsigned long)(ULONG_PTR)hwnd, c.image, (unsigned long)c.pid,
                   c.rect.right - c.rect.left, c.rect.bottom - c.rect.top,
                   c.cls, c.title);
        return TRUE;
    }

    if (eligible(&c) && !(s->skip_seen && already_seen(hwnd))) {
        if (!s->found || area(&c.rect) > area(&s->best.rect)) {
            s->best = c;
            s->found = 1;
        }
    }
    return TRUE;
}

static BOOL CALLBACK tops_for_children(HWND hwnd, LPARAM lp)
{
    /* EnumChildWindows is recursive, so this reaches grandchildren too â€” the
     * game's X window is a child of the desktop, which is itself a top-level. */
    EnumChildWindows(hwnd, child_scan_proc, lp);
    return TRUE;
}

/* One pass over top-levels, then one over their children. Both passes matter:
 * a top-level game window is the rare case, a nested one is ours. */
static void scan_all(int list, int skip_seen, struct candidate *out)
{
    struct scan s;
    memset(&s, 0, sizeof(s));
    s.list = list;
    s.skip_seen = skip_seen;

    EnumWindows(scan_proc, (LPARAM)&s);
    EnumWindows(tops_for_children, (LPARAM)&s);

    if (out) {
        if (s.found) *out = s.best;
        else memset(out, 0, sizeof(*out));
    }
}

/* Join a named wine desktop, waiting for it to exist.
 *
 * `explorer /desktop=shell` creates it at session start, so a helper spawned by
 * the app can arrive before it exists. Failing here would leave the process on
 * the default desktop, where EnumWindows returns nothing at all -- a silent
 * no-op -- so waiting is the only correct answer. Returns 1 on success. */
static int join_desktop(const char *name, DWORD wait_ms)
{
    DWORD start = GetTickCount();
    for (;;) {
        HDESK hdesk = OpenDesktopA(name, 0, FALSE, GENERIC_ALL);
        if (hdesk) {
            if (SetThreadDesktop(hdesk)) {
                printf("gn-activate: joined desktop '%s'\n", name);
                return 1;
            }
            printf("gn-activate: SetThreadDesktop('%s') failed (%lu)\n",
                   name, (unsigned long)GetLastError());
            return 0;
        }
        if (GetTickCount() - start >= wait_ms) {
            printf("gn-activate: OpenDesktop('%s') timed out (%lu)\n",
                   name, (unsigned long)GetLastError());
            return 0;
        }
        Sleep(POLL_MS * 2);
    }
}

/* Whether the desktop still exists, so a watcher ends with its session instead
 * of lingering as an orphan after the game and desktop are gone. */
static int desktop_alive(const char *name)
{
    HDESK hdesk = OpenDesktopA(name, 0, FALSE, DESKTOP_READOBJECTS);
    if (!hdesk) return 0;
    CloseDesktop(hdesk);
    return 1;
}

static int foreground_is(HWND hwnd)
{
    return GetForegroundWindow() == hwnd;
}

static void print_foreground(const char *when)
{
    struct candidate c;
    HWND fg = GetForegroundWindow();
    if (!fg) {
        printf("  %s: foreground=<none>\n", when);
        return;
    }
    describe(fg, &c);
    printf("  %s: foreground=0x%08lx %s class=%s\n", when,
           (unsigned long)(ULONG_PTR)fg, c.image[0] ? c.image : "?", c.cls);
}

static int activate(HWND hwnd, int settle_ms, int force_messages)
{
    DWORD target_tid, fg_tid;

    if (foreground_is(hwnd)) {
        printf("  already foreground\n");
        return 0;
    }

    /* Attempt 1: the direct call. A process that has just started is allowed
     * to take the foreground, which is why this usually works for us and would
     * not work for a long-lived background process. */
    if (g_verbose) printf("  SetForegroundWindow\n");
    SetForegroundWindow(hwnd);
    Sleep(settle_ms);
    if (foreground_is(hwnd)) {
        printf("  SetForegroundWindow: foreground\n");
        return 0;
    }
    if (g_verbose) print_foreground("after SetForegroundWindow");

    /* Attempt 2: join the input queues of the calling thread, the current
     * foreground thread and the target's thread, then retry, then detach. This
     * is the documented escalation when the foreground lock refuses attempt 1,
     * and wine honours it. */
    target_tid = GetWindowThreadProcessId(hwnd, NULL);
    fg_tid = GetWindowThreadProcessId(GetForegroundWindow(), NULL);
    if (g_verbose) printf("  AttachThreadInput(target=%lu fg=%lu)\n",
                          (unsigned long)target_tid, (unsigned long)fg_tid);
    if (target_tid) AttachThreadInput(GetCurrentThreadId(), target_tid, TRUE);
    if (fg_tid && fg_tid != target_tid)
        AttachThreadInput(GetCurrentThreadId(), fg_tid, TRUE);

    SetForegroundWindow(hwnd);
    SetActiveWindow(hwnd);
    Sleep(settle_ms);

    if (fg_tid && fg_tid != target_tid)
        AttachThreadInput(GetCurrentThreadId(), fg_tid, FALSE);
    if (target_tid) AttachThreadInput(GetCurrentThreadId(), target_tid, FALSE);

    if (foreground_is(hwnd)) {
        printf("  AttachThreadInput+SetForegroundWindow: foreground\n");
        return 0;
    }
    if (g_verbose) print_foreground("after AttachThreadInput");

    /* Attempt 3, opt-in only: convince the game it is active. Kept behind a
     * flag because wine's input routing does not follow it, so a title can end
     * up taking mouse but not keys -- a half fix that looks like progress. */
    if (force_messages) {
        printf("  --force-messages: SendMessage WM_ACTIVATE/WM_SETFOCUS\n");
        SendMessageA(hwnd, WM_ACTIVATE, WA_ACTIVE, 0);
        SendMessageA(hwnd, WM_SETFOCUS, 0, 0);
        Sleep(settle_ms);
        printf("  messages sent (wine foreground unchanged by design)\n");
        return 0;
    }

    print_foreground("refused");
    return 1;
}

static void usage(const char *argv0)
{
    fprintf(stderr,
            "usage: %s [options] <image.exe> [image.exe ...]\n"
            "  --timeout-ms N     wait for the window (default 120000)\n"
            "  --settle-ms N      wait after an activation attempt before\n"
            "                     reading the foreground back (default 300)\n"
            "  --force-messages   fall back to SendMessage WM_ACTIVATE if\n"
            "                     SetForegroundWindow is refused\n"
            "  --watch            activate each eligible window once, as it appears\n"
            "  --watch-ms N       poll interval in watch mode (default 400)\n"
            "  --idle-ms N        exit after N ms with no window to activate\n"
            "                     (default 60000; 0 disables)\n"
            "  --desktop NAME     join this wine desktop first (default shell;\n"
            "                     'none' stays put)\n"
            "  --query-fg         print wine's foreground window and exit\n"
            "  --list             print visible windows and exit\n"
            "  --verbose          per-attempt detail\n",
            argv0);
}

int main(int argc, char **argv)
{
    DWORD timeout_ms = 120000, settle_ms = 300, watch_ms = 400, idle_ms = 60000;
    int force_messages = 0, list = 0, watch = 0, query_fg = 0;
    const char *desktop = "shell";
    DWORD start;

    g_own_pid = GetCurrentProcessId();

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (!strcmp(a, "--watch")) {
            watch = 1;
        } else if (!strcmp(a, "--watch-ms") && i + 1 < argc) {
            watch_ms = (DWORD)strtoul(argv[++i], NULL, 10);
            /* A poll faster than this is a busy loop on a window list that only
             * changes on map/unmap; slower than a game's launch gap and the
             * activation lands after the title has already drawn itself idle. */
            if (watch_ms < 50) watch_ms = 50;
        } else if (!strcmp(a, "--idle-ms") && i + 1 < argc) {
            idle_ms = (DWORD)strtoul(argv[++i], NULL, 10);
        } else if (!strcmp(a, "--timeout-ms") && i + 1 < argc) {
            timeout_ms = (DWORD)strtoul(argv[++i], NULL, 10);
        } else if (!strcmp(a, "--settle-ms") && i + 1 < argc) {
            settle_ms = (DWORD)strtoul(argv[++i], NULL, 10);
        } else if (!strcmp(a, "--force-messages")) {
            force_messages = 1;
        } else if (!strcmp(a, "--desktop") && i + 1 < argc) {
            desktop = argv[++i];
        } else if (!strcmp(a, "--query-fg")) {
            query_fg = 1;
        } else if (!strcmp(a, "--list")) {
            list = 1;
        } else if (!strcmp(a, "--verbose")) {
            g_verbose = 1;
        } else if (a[0] == '-') {
            fprintf(stderr, "%s: unknown option '%s'\n", argv[0], a);
            usage(argv[0]);
            return 3;
        } else if (g_ntargets < MAX_TARGETS) {
            snprintf(g_targets[g_ntargets].image,
                     sizeof(g_targets[g_ntargets].image), "%s", a);
            lower(g_targets[g_ntargets].image);
            g_ntargets++;
        } else {
            fprintf(stderr, "%s: too many targets\n", argv[0]);
            return 3;
        }
    }

    /* No image argument means "whatever the session puts in front of us": by
     * rule, since at session start only the launcher's name is known. */
    g_any = (g_ntargets == 0);

    if (query_fg) {
        if (desktop && strcmp(desktop, "none") != 0) join_desktop(desktop, DESKTOP_WAIT_MS);
        printf("foreground: ");
        print_foreground("wine");
        return 0;
    }

    if (list) {
        if (desktop && strcmp(desktop, "none") != 0) join_desktop(desktop, DESKTOP_WAIT_MS);
        printf("-- visible windows --\n");
        scan_all(1, 0, NULL);
        return 0;
    }

    /* Windows live on a desktop, and explorer's /desktop=shell put the game on a
     * desktop of that name. A fresh wine process starts on the default desktop
     * and sees nothing at all, so join the named one before enumerating. This
     * must happen before any window is created on this thread. */
    int joined = 0;
    if (desktop && strcmp(desktop, "none") != 0) joined = join_desktop(desktop, DESKTOP_WAIT_MS);

    if (watch) {
        printf("gn-activate: watching (poll %lums, idle exit %lums)%s\n",
               (unsigned long)watch_ms, (unsigned long)idle_ms,
               joined ? "" : ", not joined");
        fflush(stdout);
        DWORD idle_start = GetTickCount();
        for (;;) {
            struct candidate c;
            scan_all(0, 1, &c);
            if (c.hwnd) {
                printf("gn-activate: window 0x%08lx %s pid=%lu %ldx%ld class=%s\n",
                       (unsigned long)(ULONG_PTR)c.hwnd,
                       c.image[0] ? c.image : "?", (unsigned long)c.pid,
                       c.rect.right - c.rect.left, c.rect.bottom - c.rect.top,
                       c.cls);
                fflush(stdout);
                /* Mark before acting, so a refused activation is not retried on
                 * every poll: that would be a focus fight, not a fix. */
                mark_seen(c.hwnd);
                activate(c.hwnd, (int)settle_ms, force_messages);
                /* any window worth acting on restarts the idle clock */
                idle_start = GetTickCount();
            } else if (idle_ms != 0 && GetTickCount() - idle_start >= idle_ms) {
                printf("gn-activate: nothing to activate for %lums, exiting\n",
                       (unsigned long)idle_ms);
                return 0;
            }
            if (joined && !desktop_alive(desktop)) {
                printf("gn-activate: desktop '%s' gone, exiting\n", desktop);
                return 0;
            }
            Sleep(watch_ms);
        }
    }

    if (g_ntargets == 0) {
        usage(argv[0]);
        return 3;
    }

    printf("gn-activate: waiting for");
    for (int i = 0; i < g_ntargets; i++) printf(" %s", g_targets[i].image);
    printf(" (timeout %lums)\n", (unsigned long)timeout_ms);
    fflush(stdout);

    start = GetTickCount();
    for (;;) {
        struct candidate c;
        memset(&c, 0, sizeof(c));
        scan_all(0, 0, &c);

        if (c.hwnd) {
            printf("gn-activate: window 0x%08lx %s pid=%lu %ldx%ld class=%s\n",
                   (unsigned long)(ULONG_PTR)c.hwnd, c.image,
                   (unsigned long)c.pid, c.rect.right - c.rect.left,
                   c.rect.bottom - c.rect.top, c.cls);
            return activate(c.hwnd, (int)settle_ms, force_messages);
        }

        /* GetTickCount wraps every ~49 days; the subtraction stays right. */
        if (GetTickCount() - start >= timeout_ms) {
            printf("gn-activate: no window after %lums\n",
                   (unsigned long)timeout_ms);
            return 2;
        }
        Sleep(POLL_MS);
    }
}
