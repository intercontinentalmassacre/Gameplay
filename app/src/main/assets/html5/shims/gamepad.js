// navigator.getGamepads() reads state synchronously from the host bridge. a 10Hz loop, not rAF (a bridge call +
// JSON.parse every frame is wasted at idle), fires gamepadconnected for games that listen instead of poll.
(function () {
    'use strict';
    var BRIDGE_NAME = '__gnGamepadBridge';
    var CONNECTED_ID = 'GameNative Controller (Standard Gamepad Vendor: 0000 Product: 0000)';
    var TICK_INTERVAL_MS = 100;

    var cachedGamepad = null;
    var connected = false;

    function readFromBridge() {
        var bridge = window[BRIDGE_NAME];
        if (!bridge || typeof bridge.readState !== 'function') return null;
        try {
            var raw = bridge.readState();
            if (!raw) return null;
            var arr = JSON.parse(raw);
            return (arr && arr.length > 0) ? arr[0] : null;
        } catch (e) {
            return null;
        }
    }

    // like chromium's GamepadButton, the descriptors live on the prototype. some games copy
    // Object.getOwnPropertyDescriptor(Object.getPrototypeOf(button), "pressed") onto the button; on a plain object
    // that descriptor is undefined, defineProperty throws inside the render ticker, and the game freezes.
    var GAMEPAD_BUTTON_PROTO = (function () {
        var p = {};
        function getter(field, dflt) {
            return function () {
                var v = this['_' + field];
                return v !== undefined ? v : dflt;
            };
        }
        Object.defineProperty(p, 'pressed', { get: getter('pressed', false), configurable: true, enumerable: true });
        Object.defineProperty(p, 'touched', { get: getter('touched', false), configurable: true, enumerable: true });
        Object.defineProperty(p, 'value',   { get: getter('value',   0),     configurable: true, enumerable: true });
        return p;
    })();

    function buildButton(b) {
        var btn = Object.create(GAMEPAD_BUTTON_PROTO);
        btn._pressed = !!b.pressed;
        btn._touched = !!b.touched;
        btn._value = +b.value;
        return btn;
    }

    function buildGamepadObj(data) {
        return {
            id: CONNECTED_ID,
            index: 0,
            mapping: 'standard',
            connected: true,
            timestamp: (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now(),
            buttons: data.buttons.map(buildButton),
            axes: data.axes.map(function (a) { return +a; }),
        };
    }

    // mask the native gamepad entirely, NO fallback: the bridge is the only source. a leaked physical pad breaks
    // strict consumers, e.g. Unity's InputSystem builds a HID layout from the real device id and throws
    // "exceeds 511-bit" on some pads.
    navigator.getGamepads = function () {
        if (!window[BRIDGE_NAME].hasPhysicalController()) return [null, null, null, null];
        var data = readFromBridge();
        if (!data) return [null, null, null, null];
        cachedGamepad = buildGamepadObj(data);
        return [cachedGamepad, null, null, null];
    };

    // likewise drop native connect/disconnect events; only ours (CONNECTED_ID) reach the game.
    ['gamepadconnected', 'gamepaddisconnected'].forEach(function (type) {
        window.addEventListener(type, function (e) {
            if (!e.gamepad || e.gamepad.id !== CONNECTED_ID) {
                e.stopImmediatePropagation();
            }
        }, true);
    });

    // These are the touch-pad layers in GB Studio and PICO-8 web exports.
    // Leave the actual canvas, start prompt and pause/fullscreen menus interactive.
    var touchStyle;
    function setTouchPadHidden(hidden) {
        if (!hidden) {
            if (touchStyle) touchStyle.remove();
            touchStyle = null;
            return;
        }
        if (!document.head || (touchStyle && touchStyle.isConnected)) return;
        touchStyle = document.createElement('style');
        touchStyle.textContent = '#gameboy_shell #controller, #game + #controller, .ejs_virtualGamepad_parent, ' +
            '#p8_playarea #touch_controls_gfx, ' +
            '#p8_playarea #touch_controls_background {display:none!important}';
        document.head.appendChild(touchStyle);
    }

    // PICO-8 web exports read player one's six bits from pico8_buttons[0].
    // Combine with the page's touch controls; -1 lets the cart read its own
    // keyboard when neither the page nor the physical pad is sending input.
    var picoPlayers = null;
    function attachPicoInput() {
        if (!(window.Module && typeof window.Module.pico8Reset === 'function') &&
            !('pico8_gpio' in window) && !('pico8_buttons' in window)) return;
        var players = window.pico8_buttons;
        if (players == null) players = window.pico8_buttons = Array(8).fill(-1);
        if (players === picoPlayers || !Array.isArray(players)) return;
        var slot = Object.getOwnPropertyDescriptor(players, '0');
        if (slot && (!slot.configurable || slot.get || slot.set)) return;
        var pageButtons = players[0];
        Object.defineProperty(players, '0', {
            enumerable: true, configurable: true,
            get: function () {
                var bridge = window[BRIDGE_NAME];
                var pad = bridge ? bridge.readPicoButtons() : 0;
                if (!pad) return typeof pageButtons === 'number' ? pageButtons : -1;
                return pad | (pageButtons >= 0 ? pageButtons : 0);
            },
            set: function (value) { pageButtons = value; }
        });
        picoPlayers = players;
    }

    function tick() {
        attachPicoInput();
        var present = window[BRIDGE_NAME].hasPhysicalController();
        setTouchPadHidden(present);
        if (document.readyState !== 'complete' || present === connected) return;
        var data = readFromBridge();
        if (!data) return;
        connected = present;
        cachedGamepad = buildGamepadObj(data);
        cachedGamepad.connected = present;
        try {
            var change = new Event(present ? 'gamepadconnected' : 'gamepaddisconnected');
            change.gamepad = cachedGamepad;
            window.dispatchEvent(change);
        } catch (e) {}
    }
    setInterval(tick, TICK_INTERVAL_MS);
})();
