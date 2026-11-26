#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define TAG "FexNativeLauncher"

static char **to_array(JNIEnv *env, jobjectArray array, int *out_count) {
    int count = array ? (*env)->GetArrayLength(env, array) : 0;
    char **result = calloc((size_t) count + 1, sizeof(char *));
    for (int i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        result[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, item, chars);
        (*env)->DeleteLocalRef(env, item);
    }
    result[count] = NULL;
    *out_count = count;
    return result;
}

static void free_array(char **array, int count) {
    for (int i = 0; i < count; i++) free(array[i]);
    free(array);
}

static void child_exec(const char *wine, char **argv, int argc, char **envp, const char *log_path) {
    const char *cwd = getenv("FEX_LAUNCHER_CWD");
    if (cwd && cwd[0] && chdir(cwd) != 0) {
        // Keep going; Wine reports the eventual path error if the working directory matters.
    }
    int fd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
    }

    execve(wine, argv, envp);

    // Android may deny executing an app-private ELF directly. Fall back to the dynamic linker
    // with the wine path as argv[0], which is the shape the working run-as reproduction uses.
    int first_error = errno;
    char **linker_argv = calloc((size_t) argc + 2, sizeof(char *));
    linker_argv[0] = (char *) wine;
    linker_argv[1] = (char *) wine;
    for (int i = 1; i < argc; i++) linker_argv[i + 1] = argv[i];
    execve("/system/bin/linker64", linker_argv, envp);

    dprintf(STDERR_FILENO, "execve(%s) failed: %s; linker64 failed: %s\n",
            wine, strerror(first_error), strerror(errno));
    _exit(127);
}

JNIEXPORT jint JNICALL
Java_com_winlator_fexcore_FexNativeLauncher_runNative(
        JNIEnv *env, jclass clazz,
        jstring wine_path, jobjectArray args, jobjectArray env_vars, jstring log_path) {
    const char *wine = (*env)->GetStringUTFChars(env, wine_path, NULL);
    const char *log = (*env)->GetStringUTFChars(env, log_path, NULL);
    int argc = 0;
    int envc = 0;
    char **argv = to_array(env, args, &argc);
    char **envp = to_array(env, env_vars, &envc);

    // argv[0] is the wine binary itself, not linker64.
    char **full_argv = calloc((size_t) argc + 2, sizeof(char *));
    full_argv[0] = strdup(wine);
    for (int i = 0; i < argc; i++) full_argv[i + 1] = argv[i];
    full_argv[argc + 1] = NULL;

    pid_t pid = fork();
    if (pid == 0) {
        child_exec(wine, full_argv, argc + 1, envp, log);
    }
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "fork failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, wine_path, wine);
        (*env)->ReleaseStringUTFChars(env, log_path, log);
        return -1;
    }

    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
    }

    (*env)->ReleaseStringUTFChars(env, wine_path, wine);
    (*env)->ReleaseStringUTFChars(env, log_path, log);
    free_array(argv, argc);
    free_array(envp, envc);
    free(full_argv[0]);
    free(full_argv);

    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
