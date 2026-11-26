package com.winlator.fexcore;

/**
 * Small fork/exec bridge for the FEX offline compiler.
 *
 * The regular launcher prefixes /system/bin/linker64; the ARM64EC compiler child then resolves
 * Wine's builtins from the linker and fails with "could not load kernel32.dll". This bridge
 * execs wine directly (falling back to linker64 with wine as argv[0]) so the compiler sees the
 * same process shape as the validated run-as reproduction.
 */
public final class FexNativeLauncher {
    static {
        System.loadLibrary("fexlauncher");
    }

    private FexNativeLauncher() {
    }

    /**
     * @param winePath absolute path to the wine ELF
     * @param args arguments passed to wine (e.g. the Windows compiler path and "process-all")
     * @param envVars KEY=VALUE array; the child gets exactly this environment
     * @param logPath file that receives the child's stdout/stderr
     * @return the child exit code, or -1 on fork failure
     */
    public static native int runNative(String winePath, String[] args, String[] envVars, String logPath);

    public static int runCompiler(String winePath, String windowsCompilerPath, String logPath, String[] envVars) {
        String[] args = {windowsCompilerPath, "process-all"};
        return runNative(winePath, args, envVars, logPath);
    }

    public static int runCompiler32(String winePath, String windowsCompilerPath, String windowsCacheDir,
                                    String windowsCodeMap, String logPath, String[] envVars) {
        String[] args = {
            windowsCompilerPath,
            "generate",
            "--outdir", windowsCacheDir + "cache",
            windowsCodeMap,
        };
        return runNative(winePath, args, envVars, logPath);
    }
}
