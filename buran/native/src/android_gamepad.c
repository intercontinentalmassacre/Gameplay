#define _GNU_SOURCE
#include <jni.h>
#include <limits.h>
#include <linux/futex.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

/* Keep the WinHandler layout in sync with android/gamescope/runtime-gamepad.c. */
JNIEXPORT void JNICALL
Java_org_buran_runtime_PhysicalGamepad_writeState(
    JNIEnv *env, jobject receiver, jobject mapping, jbyteArray state, jboolean connected
) {
    (void)receiver;
    if ((*env)->GetDirectBufferCapacity(env, mapping) != 64 ||
        (*env)->GetArrayLength(env, state) != 28) return;
    uint8_t *bytes = (*env)->GetDirectBufferAddress(env, mapping);
    if (!bytes) return;
    jbyte snapshot[28];
    (*env)->GetByteArrayRegion(env, state, 0, sizeof(snapshot), snapshot);
    if ((*env)->ExceptionCheck(env)) return;

    _Atomic uint32_t *sequence = (_Atomic uint32_t *)bytes;
    atomic_fetch_add_explicit(sequence, 1, memory_order_acq_rel);
    memcpy(bytes + 4, snapshot, sizeof(snapshot));
    atomic_store_explicit((_Atomic uint32_t *)(bytes + 40), connected != JNI_FALSE, memory_order_relaxed);
    atomic_fetch_add_explicit(sequence, 1, memory_order_release);
    syscall(SYS_futex, sequence, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}
