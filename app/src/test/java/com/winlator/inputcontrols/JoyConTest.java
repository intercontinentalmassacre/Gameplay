package com.winlator.inputcontrols;

import android.view.KeyEvent;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class JoyConTest {
    @Test public void pairsStaySeparateAndSurvivingHalfKeepsItsPlayer() {
        JoyCon.Pairs pairs = new JoyCon.Pairs();
        String first = pairs.identifier(10, JoyCon.RIGHT);
        String second = pairs.identifier(11, JoyCon.RIGHT);
        assertNotEquals(first, second);
        assertEquals(first, pairs.identifier(12, JoyCon.LEFT));
        assertEquals(second, pairs.identifier(13, JoyCon.LEFT));
        pairs.retain(Set.of(10, 11, 13));
        assertEquals(first, pairs.identifier(10, JoyCon.RIGHT));
        assertEquals(first, pairs.identifier(14, JoyCon.LEFT));
        assertEquals(second, pairs.identifier(13, JoyCon.LEFT));
    }

    @Test public void disconnectReleasesOnlyTheMissingHalfsControls() {
        GamepadState left = new GamepadState(), right = new GamepadState(), output = new GamepadState();
        left.thumbLY = -1;
        left.dpad[2] = true;
        left.triggerL = 1;
        right.thumbRX = 0.5f;
        right.setPressed(ExternalController.IDX_BUTTON_A, true);
        JoyCon.combine(output, left, right);
        assertEquals(-1, output.thumbLY, 0);
        assertEquals(0.5f, output.thumbRX, 0);
        assertTrue(output.dpad[2]);
        JoyCon.combine(output, null, right);
        assertFalse(output.dpad[2]);
        assertEquals(0, output.triggerL, 0);
        assertEquals(0, output.thumbLY, 0);
        assertTrue(output.isPressed(ExternalController.IDX_BUTTON_A));
        assertEquals(0.5f, output.thumbRX, 0);
    }

    @Test public void shellAndGameReceiveStandardNavigationButtons() {
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, JoyCon.keyCode(JoyCon.LEFT, 0x220, 0));
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, JoyCon.keyCode(JoyCon.RIGHT, 0x130, 0));
        assertEquals(KeyEvent.KEYCODE_BUTTON_R2, JoyCon.keyCode(JoyCon.RIGHT, 0, KeyEvent.KEYCODE_BUTTON_R2));
    }
}
