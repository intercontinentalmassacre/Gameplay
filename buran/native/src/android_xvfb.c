#include <android/bitmap.h>
#include <jni.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

extern bool buran_xvfb_copy(
    const uint8_t *source, size_t source_size, size_t source_stride,
    uint8_t *destination, size_t destination_size, size_t destination_stride,
    size_t width, size_t height, bool big_endian
);

JNIEXPORT jboolean JNICALL
Java_org_buran_runtime_display_XvfbPixels_copy(
    JNIEnv *env, jclass type, jobject buffer, jint stride, jboolean big_endian, jobject bitmap
) {
    (void)type;
    if (!buffer || !bitmap)
        return JNI_FALSE;
    const uint8_t *source = (*env)->GetDirectBufferAddress(env, buffer);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);
    AndroidBitmapInfo info;
    if (!source || capacity <= 0 || (uint64_t)capacity > SIZE_MAX || stride <= 0 ||
        AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.height ||
        info.stride > SIZE_MAX / info.height) {
        return JNI_FALSE;
    }

    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    bool copied = buran_xvfb_copy(
        source, (size_t)capacity, (size_t)stride, pixels,
        (size_t)info.stride * info.height, info.stride, info.width, info.height,
        big_endian == JNI_TRUE
    );
    int unlocked = AndroidBitmap_unlockPixels(env, bitmap);
    return copied && unlocked == ANDROID_BITMAP_RESULT_SUCCESS ? JNI_TRUE : JNI_FALSE;
}
