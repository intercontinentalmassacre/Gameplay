package com.droiddeck.launcher.wayland;

import android.view.Surface;

/** The binary JNI contract of DroidDeck 0.7.11. */
public final class WaylandCompositor {
    static {
        System.loadLibrary("droiddeckwayland");
    }

    private WaylandCompositor() {}

    // JNI_OnLoad resolves every callback, including those Buran does not consume yet.
    static void onFirstFramePresented() {}
    static void onGameSurface(String window, String gpuName) {}
    static void onGameFrame() {}
    static void onCompositorFrame() {}
    static void onGameProgram(int pid, String program) {}
    static void onPointerLock(boolean locked, int x, int y) {}
    static void onClipboardText(byte[] utf8) {}
    static void onTextInput(boolean enabled, String program, int x, int y, int w, int h) {}
    static void onOverlayPlanes(int planes) {}
    static void onRefreshTarget(int refresh, int frameRate) {}
    static void onContentRate(int fps) {}

    public static native void nativeStartWithSurface(
        Surface surface, String runtimeDirectory, String driverPath, String libraryName,
        String nativeLibDir, boolean allowDriverFallback
    );
    public static native void nativeSetSurface(Surface surface);
    public static native boolean nativeWaitSurface(int timeoutMs);
    public static native void nativeSetOutputSize(int width, int height);
    public static native void nativeSetFpsLimit(int fps);
    public static native void nativeVsync(long frameTimeNanos);
    public static native String nativeCompositorFault();
    public static native void nativeSendPointer(int action, int x, int y);
    public static native void nativeSendSceneInput(int type, int a, int b);
    public static native void nativeSendTouch(int action, int pointerId, int x, int y);
    public static native void nativeSendKey(int evdev, int state);
}
