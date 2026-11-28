#include <jni.h>
#include <stddef.h>
#include <stdint.h>

extern int buran_copy_game_file(
    const uint8_t *staging, size_t staging_len,
    const uint8_t *relative, size_t relative_len,
    int fd, int64_t expected_size,
    uint8_t *error, size_t error_len
);

JNIEXPORT jstring JNICALL
Java_org_buran_runtime_NativeGameImport_copy(
    JNIEnv *env, jobject receiver, jbyteArray staging, jbyteArray relative, jint fd, jlong expected_size
) {
    (void)receiver;
    jbyte staging_path[4095];
    jbyte relative_path[4095];
    if (!staging || !relative)
        return (*env)->NewStringUTF(env, "missing game import path");
    jsize staging_len = (*env)->GetArrayLength(env, staging);
    jsize relative_len = (*env)->GetArrayLength(env, relative);
    if (staging_len <= 0 || (size_t)staging_len > sizeof(staging_path) ||
        relative_len <= 0 || (size_t)relative_len > sizeof(relative_path)) {
        return (*env)->NewStringUTF(env, "invalid game import path length");
    }
    (*env)->GetByteArrayRegion(env, staging, 0, staging_len, staging_path);
    if ((*env)->ExceptionCheck(env))
        return NULL;
    (*env)->GetByteArrayRegion(env, relative, 0, relative_len, relative_path);
    if ((*env)->ExceptionCheck(env))
        return NULL;

    uint8_t error[256];
    int status = buran_copy_game_file(
        (const uint8_t *)staging_path, (size_t)staging_len,
        (const uint8_t *)relative_path, (size_t)relative_len,
        fd, expected_size, error, sizeof(error)
    );
    return status == 0 ? NULL : (*env)->NewStringUTF(env, (const char *)error);
}
