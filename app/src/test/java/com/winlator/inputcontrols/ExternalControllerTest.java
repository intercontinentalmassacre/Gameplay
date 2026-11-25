package com.winlator.inputcontrols;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.InputDeviceBuilder;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ExternalControllerTest {
    @Test
    public void rejectsXiaomiUinputEvenWhenAndroidReportsGamepadSources() {
        assertFalse(ExternalController.isGameController(gamepad("Uinput-xiaomi", false)));
        assertFalse(ExternalController.isGameController(gamepad("ui-input-xiaomi", false)));
    }

    @Test
    public void stillAcceptsPhysicalControllers() {
        assertTrue(ExternalController.isGameController(gamepad("Xbox Wireless Controller", true)));
        assertTrue(ExternalController.isGameController(gamepad("Built-in Gamepad", false)));
    }

    @Test
    public void hybridDpadAndKeyboardArrowsUseTheirOwnSources() {
        InputDevice hybrid = InputDeviceBuilder.newBuilder()
                .setId(42)
                .setName("Keyboard with gamepad")
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
                .setSources(InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)
                .addMotionRange(MotionEvent.AXIS_HAT_X, InputDevice.SOURCE_JOYSTICK, -1, 1, 0, 0, 0)
                .build();
        KeyEvent arrow = spy(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        doReturn(hybrid).when(arrow).getDevice();
        arrow.setSource(InputDevice.SOURCE_KEYBOARD);
        assertFalse(ExternalController.isControllerKeyEvent(arrow));
        arrow.setSource(InputDevice.SOURCE_DPAD);
        assertTrue(ExternalController.isControllerKeyEvent(arrow));
    }

    private static InputDevice gamepad(String name, boolean external) {
        return InputDeviceBuilder.newBuilder()
                .setId(1)
                .setName(name)
                .setExternal(external)
                .setSources(InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK)
                .build();
    }
}
