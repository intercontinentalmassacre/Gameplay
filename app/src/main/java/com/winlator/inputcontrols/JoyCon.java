package com.winlator.inputcontrols;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class JoyCon {
    static final int LEFT = 0, RIGHT = 1;

    public static int side(InputDevice device) {
        if (device == null || device.isVirtual() || device.getVendorId() != 0x057e) return -1;
        switch (device.getProductId()) {
            case 0x2006: return LEFT;
            case 0x2007: return RIGHT;
            default: return -1;
        }
    }

    // Each occupied side stays in its pair until Android removes the device.
    static final class Pairs {
        private final List<int[]> pairs = new ArrayList<>();

        void retain(Set<Integer> connected) {
            for (int[] pair : pairs) {
                for (int side = 0; side < pair.length; side++) {
                    if (!connected.contains(pair[side])) pair[side] = -1;
                }
            }
        }

        String identifier(int deviceId, int side) {
            for (int i = 0; i < pairs.size(); i++) {
                if (pairs.get(i)[side] == deviceId) return "joycon_pair_" + i;
            }
            for (int i = 0; i < pairs.size(); i++) {
                if (pairs.get(i)[side] == -1 && pairs.get(i)[1 - side] != -1)
                    return assign(i, deviceId, side);
            }
            for (int i = 0; i < pairs.size(); i++) {
                if (pairs.get(i)[side] == -1) return assign(i, deviceId, side);
            }
            pairs.add(new int[]{-1, -1});
            return assign(pairs.size() - 1, deviceId, side);
        }

        private String assign(int index, int deviceId, int side) {
            pairs.get(index)[side] = deviceId;
            return "joycon_pair_" + index;
        }
    }

    public static KeyEvent normalizeKey(KeyEvent event) {
        int side = side(event.getDevice());
        if (side < 0) return event;
        int code = keyCode(side, event.getScanCode(), event.getKeyCode());
        if (code == event.getKeyCode()) return event;
        return new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(), code,
                event.getRepeatCount(), event.getMetaState(), event.getDeviceId(), event.getScanCode(),
                event.getFlags(), event.getSource());
    }

    static int keyCode(int side, int scan, int fallback) {
        // Linux evdev reports the physical button even when the Android key layout differs.
        if (side == LEFT) {
            switch (scan) {
                case 0x220: return KeyEvent.KEYCODE_DPAD_UP;
                case 0x221: return KeyEvent.KEYCODE_DPAD_DOWN;
                case 0x222: return KeyEvent.KEYCODE_DPAD_LEFT;
                case 0x223: return KeyEvent.KEYCODE_DPAD_RIGHT;
                case 0x136: return KeyEvent.KEYCODE_BUTTON_L1;
                case 0x138: return KeyEvent.KEYCODE_BUTTON_L2;
                case 0x13a: return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 0x13d: return KeyEvent.KEYCODE_BUTTON_THUMBL;
            }
        } else {
            switch (scan) {
                case 0x130: return KeyEvent.KEYCODE_BUTTON_A;
                case 0x131: return KeyEvent.KEYCODE_BUTTON_B;
                case 0x133: return KeyEvent.KEYCODE_BUTTON_Y;
                case 0x134: return KeyEvent.KEYCODE_BUTTON_X;
                case 0x137: return KeyEvent.KEYCODE_BUTTON_R1;
                case 0x139: return KeyEvent.KEYCODE_BUTTON_R2;
                case 0x13b: return KeyEvent.KEYCODE_BUTTON_START;
                case 0x13e: return KeyEvent.KEYCODE_BUTTON_THUMBR;
            }
        }
        return fallback;
    }

    public static void combine(GamepadState output, GamepadState left, GamepadState right) {
        output.thumbLX = left == null ? 0 : left.thumbLX;
        output.thumbLY = left == null ? 0 : left.thumbLY;
        output.triggerL = left == null ? 0 : left.triggerL;
        output.thumbRX = right == null ? 0 : right.thumbRX;
        output.thumbRY = right == null ? 0 : right.thumbRY;
        output.triggerR = right == null ? 0 : right.triggerR;
        output.buttons = (short) ((left == null ? 0 : left.buttons) | (right == null ? 0 : right.buttons));
        for (int i = 0; i < output.dpad.length; i++) output.dpad[i] = left != null && left.dpad[i];
    }

    static void readStick(GamepadState state, MotionEvent event, int side) {
        InputDevice device = event.getDevice();
        if (device == null) return;
        if (side == LEFT) {
            state.thumbLX = ExternalController.getCenteredAxis(event, MotionEvent.AXIS_X, -1);
            state.thumbLY = ExternalController.getCenteredAxis(event, MotionEvent.AXIS_Y, -1);
            if (device.getMotionRange(MotionEvent.AXIS_HAT_X, event.getSource()) != null) {
                float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
                float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
                state.dpad[0] = y < 0;
                state.dpad[1] = x > 0;
                state.dpad[2] = y > 0;
                state.dpad[3] = x < 0;
            }
        } else {
            int x = MotionEvent.AXIS_X, y = MotionEvent.AXIS_Y;
            if (device.getMotionRange(MotionEvent.AXIS_RX, event.getSource()) != null) {
                x = MotionEvent.AXIS_RX;
                y = MotionEvent.AXIS_RY;
            } else if (device.getMotionRange(MotionEvent.AXIS_Z, event.getSource()) != null) {
                x = MotionEvent.AXIS_Z;
                y = MotionEvent.AXIS_RZ;
            }
            state.thumbRX = ExternalController.getCenteredAxis(event, x, -1);
            state.thumbRY = ExternalController.getCenteredAxis(event, y, -1);
        }
    }
}
