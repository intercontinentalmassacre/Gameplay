package com.winlator.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pure part of the no-_NET_WM_WOW64 fallback: which CPU mask a window's process gets once
 * the winhandler has said whether it is a 32-bit (WoW64) process.
 */
public class Win32AppWorkaroundsAffinityTest {
    private static final int MAIN_MASK = 0xFF;       // all 8 cores
    private static final int WOW64_MASK = 0x0F;      // lower half, set explicitly
    private static final int DEFAULT_WOW64 = 0xF0;   // upper half, untouched default

    @Test
    public void explicitWoW64ListReachesA32BitProcess() {
        assertEquals(WOW64_MASK,
            Win32AppWorkarounds.resolveWindowAffinityMask(MAIN_MASK, WOW64_MASK, DEFAULT_WOW64, true));
    }

    @Test
    public void explicitWoW64ListIsNotSentToA64BitProcess() {
        assertEquals(MAIN_MASK,
            Win32AppWorkarounds.resolveWindowAffinityMask(MAIN_MASK, WOW64_MASK, DEFAULT_WOW64, false));
    }

    @Test
    public void untouchedDefaultWoW64ListIsNotExplicit() {
        assertEquals(MAIN_MASK,
            Win32AppWorkarounds.resolveWindowAffinityMask(MAIN_MASK, DEFAULT_WOW64, DEFAULT_WOW64, true));
    }

    @Test
    public void zeroWoW64ListIsNotExplicit() {
        assertEquals(MAIN_MASK,
            Win32AppWorkarounds.resolveWindowAffinityMask(MAIN_MASK, 0, DEFAULT_WOW64, true));
    }

    @Test
    public void a32BitProcessGetsTheExplicitListEvenWhenTheMainListIsZero() {
        assertEquals(WOW64_MASK,
            Win32AppWorkarounds.resolveWindowAffinityMask(0, WOW64_MASK, DEFAULT_WOW64, true));
    }

    @Test
    public void a64BitProcessWithNoMainListGetsNoRequest() {
        assertEquals(0,
            Win32AppWorkarounds.resolveWindowAffinityMask(0, WOW64_MASK, DEFAULT_WOW64, false));
    }

    @Test
    public void onlyANonDefaultNonEmptyWoW64ListIsExplicit() {
        assertTrue(Win32AppWorkarounds.isExplicitWoW64Mask(0xC0, DEFAULT_WOW64));
        assertFalse(Win32AppWorkarounds.isExplicitWoW64Mask(DEFAULT_WOW64, DEFAULT_WOW64));
        assertFalse(Win32AppWorkarounds.isExplicitWoW64Mask(0, DEFAULT_WOW64));
    }

    @Test
    public void sixteenBitMasksCompareAfterSignExtension() {
        // The task affinity fields are shorts; a full 16-core default mask is -1 as an int.
        assertFalse(Win32AppWorkarounds.isExplicitWoW64Mask(-1, -1));
        assertEquals(-1, Win32AppWorkarounds.resolveWindowAffinityMask(-1, -1, -1, true));
    }
}
