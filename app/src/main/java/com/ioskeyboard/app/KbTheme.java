package com.ioskeyboard.app;

import android.content.Context;
import android.content.res.Configuration;

/** iOS-like colour palettes. */
final class KbTheme {
    final boolean dark;
    /** glass = tint drawn over the blurred app behind; solid = fallback when blur is unavailable */
    final int glass, solid, key, keyPressed, funcPressed, text, rim, accent, accentText, shiftOn,
            shiftOnText, divider, bubble, stripPress, altSel, pill, pillPressed, pillRim, pillText;
    /** kept for the emoji panel */
    final int bg, func;

    private KbTheme(boolean dark) {
        this.dark = dark;
        if (dark) {
            glass = 0x8C1C1B1F;
            solid = 0xF22A292E;
            key = 0x40FFFFFF;
            keyPressed = 0x80FFFFFF;
            funcPressed = 0x33FFFFFF;
            text = 0xFFFFFFFF;
            rim = 0x1AFFFFFF;
            accent = 0xFF0A84FF;
            accentText = 0xFFFFFFFF;
            shiftOn = 0xF2FFFFFF;
            shiftOnText = 0xFF000000;
            divider = 0x40FFFFFF;
            bubble = 0xFF77767C;
            stripPress = 0x26FFFFFF;
            altSel = 0xFF0A84FF;
            pill = 0xF2454349;
            pillPressed = 0xFF65636A;
            pillRim = 0x24FFFFFF;
            pillText = 0xFFFFFFFF;
        } else {
            glass = 0x99E9E9EE;
            solid = 0xF5DADCE1;
            key = 0xB3FFFFFF;
            keyPressed = 0xFFFFFFFF;
            funcPressed = 0x26000000;
            text = 0xFF000000;
            rim = 0x14000000;
            accent = 0xFF007AFF;
            accentText = 0xFFFFFFFF;
            shiftOn = 0xFFFFFFFF;
            shiftOnText = 0xFF000000;
            divider = 0x40000000;
            bubble = 0xFFFFFFFF;
            stripPress = 0x1F000000;
            altSel = 0xFF007AFF;
            pill = 0xF2F7F7FA;
            pillPressed = 0xFFDCDCE2;
            pillRim = 0x1A000000;
            pillText = 0xFF000000;
        }
        bg = 0;
        func = funcPressed;
    }

    private static final KbTheme LIGHT = new KbTheme(false), DARK = new KbTheme(true);

    /** mode: 0 = follow system, 1 = light, 2 = dark */
    static KbTheme of(Context c, int mode) {
        boolean dark;
        if (mode == 1) dark = false;
        else if (mode == 2) dark = true;
        else dark = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return dark ? DARK : LIGHT;
    }

    static int withAlpha(int c, float a) {
        int v = Math.round(Math.max(0f, Math.min(1f, a)) * 255);
        return (c & 0x00FFFFFF) | (v << 24);
    }

    static int lerp(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
