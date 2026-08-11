package com.winlator.xenvironment.components;

/**
 * CPU backend actually used for a launch. Parsing of legacy container
 * strings happens only here (Container.getEmulator() is a free-form string).
 */
enum CpuBackendType {
    BOX64_BIONIC,
    BOX64_GLIBC,
    WOWBOX64_ARM64EC,
    FEX_ARM64EC;

    static CpuBackendType resolve(boolean isArm64EC, boolean isGlibcVariant, String emulator) {
        if (isArm64EC) {
            return "fexcore".equalsIgnoreCase(emulator == null ? "" : emulator.trim())
                ? FEX_ARM64EC
                : WOWBOX64_ARM64EC;
        }
        return isGlibcVariant ? BOX64_GLIBC : BOX64_BIONIC;
    }

    boolean isBox64Family() {
        return this != FEX_ARM64EC;
    }

    boolean appliesBox64Env() {
        return this != FEX_ARM64EC;
    }

    boolean appliesFexEnv() {
        return this == FEX_ARM64EC;
    }

    /** HODLL value for ARM64EC mode, or null when no HODLL should be set. */
    String hodll() {
        switch (this) {
            case WOWBOX64_ARM64EC: return "wowbox64.dll";
            case FEX_ARM64EC: return "libwow64fex.dll";
            default: return null;
        }
    }
}
