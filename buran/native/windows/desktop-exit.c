typedef void *Handle;
typedef unsigned long Dword;
typedef unsigned short Wchar;
typedef __SIZE_TYPE__ Size;

typedef struct {
    Dword size;
    Wchar *reserved, *desktop, *title;
    Dword x, y, width, height, columns, rows, fill, flags;
    unsigned short show, reserved_size;
    unsigned char *reserved_data;
    Handle input, output, error;
} StartupInfo;

typedef struct {
    Handle process, thread;
    Dword process_id, thread_id;
} ProcessInfo;

typedef struct {
    long long user, kernel, period_user, period_kernel;
    Dword faults, total, active, terminated;
} JobAccounting;

typedef struct {
    struct {
        long long process_time, job_time;
        Dword flags;
        Size min_working_set, max_working_set;
        Dword active_limit;
        Size affinity;
        Dword priority, scheduling;
    } basic;
    unsigned long long io[6];
    Size process_memory, job_memory, peak_process_memory, peak_job_memory;
} JobLimits;

#define IMPORT __declspec(dllimport)
IMPORT Wchar *__stdcall GetCommandLineW(void);
IMPORT Dword __stdcall GetEnvironmentVariableW(const Wchar *, Wchar *, Dword);
IMPORT int __stdcall CreateProcessW(const Wchar *, Wchar *, void *, void *, int,
                                    Dword, void *, const Wchar *, StartupInfo *, ProcessInfo *);
IMPORT Handle __stdcall CreateJobObjectW(void *, const Wchar *);
IMPORT int __stdcall SetInformationJobObject(Handle, int, void *, Dword);
IMPORT int __stdcall AssignProcessToJobObject(Handle, Handle);
IMPORT int __stdcall QueryInformationJobObject(Handle, int, void *, Dword, Dword *);
IMPORT Dword __stdcall ResumeThread(Handle);
IMPORT int __stdcall TerminateProcess(Handle, unsigned int);
IMPORT Dword __stdcall WaitForSingleObject(Handle, Dword);
IMPORT int __stdcall GetExitCodeProcess(Handle, Dword *);
IMPORT void __stdcall Sleep(Dword);
IMPORT Handle __stdcall CreateFileW(const Wchar *, Dword, Dword, void *, Dword, Dword, Handle);
IMPORT int __stdcall WriteFile(Handle, const void *, Dword, Dword *, void *);
IMPORT int __stdcall CloseHandle(Handle);
IMPORT __declspec(noreturn) void __stdcall ExitProcess(unsigned int);

enum {
    CREATE_SUSPENDED = 4,
    JOB_BASIC_ACCOUNTING_INFORMATION = 1,
    JOB_EXTENDED_LIMIT_INFORMATION = 9,
    JOB_LIMIT_BREAKAWAY_OK = 0x800,
    JOB_LIMIT_KILL_ON_JOB_CLOSE = 0x2000,
    GENERIC_WRITE = 0x40000000,
    TRUNCATE_EXISTING = 5,
    FILE_ATTRIBUTE_NORMAL = 0x80
};

static StartupInfo startup = { .size = sizeof(StartupInfo) };
static ProcessInfo child;
static Wchar marker[32768];
static JobLimits limits = { .basic.flags = JOB_LIMIT_BREAKAWAY_OK | JOB_LIMIT_KILL_ON_JOB_CLOSE };

static int launch(void)
{
    Dword length = GetEnvironmentVariableW(L"BURAN_DESKTOP_EXIT", marker, 32768);
    if (!length || length >= 32768)
        return 0;

    // Drop only argv[0]; cmd.exe would reinterpret metacharacters in the remaining arguments.
    Wchar *command = GetCommandLineW();
    int quoted = 0;
    for (; *command; ++command) {
        if (*command == L'"')
            quoted = !quoted;
        if (!quoted && (*command == L' ' || *command == L'\t'))
            break;
    }
    while (*command == L' ' || *command == L'\t')
        ++command;
    if (!*command)
        return 0;

    Handle job = CreateJobObjectW(0, 0);
    if (!job)
        return 0;

    Dword code, written;
    JobAccounting accounting;
    int ok = 0;
    if (!SetInformationJobObject(job, JOB_EXTENDED_LIMIT_INFORMATION, &limits, sizeof(limits)) ||
        !CreateProcessW(0, command, 0, 0, 0, CREATE_SUSPENDED, 0, 0, &startup, &child))
        goto close_job;

    if (!AssignProcessToJobObject(job, child.process)) {
        if (TerminateProcess(child.process, 1))
            WaitForSingleObject(child.process, (Dword)-1);
        goto close_child;
    }
    if (ResumeThread(child.thread) == (Dword)-1 ||
        WaitForSingleObject(child.process, (Dword)-1) != 0 ||
        !GetExitCodeProcess(child.process, &code))
        goto close_child;

    // Wait for descendants in the job and report the original EXE's status.
    for (;;) {
        if (!QueryInformationJobObject(job, JOB_BASIC_ACCOUNTING_INFORMATION,
                                       &accounting, sizeof(accounting), 0))
            goto close_child;
        if (!accounting.active)
            break;
        Sleep(100);
    }
    Handle out = CreateFileW(marker, GENERIC_WRITE, 0, 0, TRUNCATE_EXISTING, FILE_ATTRIBUTE_NORMAL, 0);
    if (out != (Handle)-1) {
        ok = WriteFile(out, &code, sizeof(code), &written, 0) && written == sizeof(code);
        CloseHandle(out);
    }

close_child:
    CloseHandle(child.thread);
    CloseHandle(child.process);
close_job:
    CloseHandle(job);
    return ok;
}

void mainCRTStartup(void)
{
    ExitProcess(launch() ? 0 : 1);
}
