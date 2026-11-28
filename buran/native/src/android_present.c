#include <jni.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>

typedef struct PresentFrames PresentFrames;
extern PresentFrames *buran_present_open(int fd, uint8_t opcode);
extern void buran_present_track(PresentFrames *, uint32_t window, uint32_t event, bool active);
extern float buran_present_read(PresentFrames *, uint64_t now_ms);
extern void buran_present_close(PresentFrames *);

JNIEXPORT jlong JNICALL
Java_org_buran_runtime_display_PresentFramesNative_open(
    JNIEnv *env, jclass type, jint fd, jint opcode
) {
    (void)env;
    (void)type;
    int owned = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    return owned < 0 ? 0 : (jlong)(intptr_t)buran_present_open(owned, (uint8_t)opcode);
}

JNIEXPORT void JNICALL
Java_org_buran_runtime_display_PresentFramesNative_track(
    JNIEnv *env, jclass type, jlong handle, jint window, jint event, jboolean active
) {
    (void)env;
    (void)type;
    buran_present_track((PresentFrames *)(intptr_t)handle, (uint32_t)window, (uint32_t)event, active);
}

JNIEXPORT jfloat JNICALL
Java_org_buran_runtime_display_PresentFramesNative_read(
    JNIEnv *env, jclass type, jlong handle, jlong now_ms
) {
    (void)env;
    (void)type;
    return buran_present_read((PresentFrames *)(intptr_t)handle, (uint64_t)now_ms);
}

JNIEXPORT void JNICALL
Java_org_buran_runtime_display_PresentFramesNative_close(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    buran_present_close((PresentFrames *)(intptr_t)handle);
}
