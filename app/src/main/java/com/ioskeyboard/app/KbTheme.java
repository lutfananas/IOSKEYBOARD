package com.ioskeyboard.app;

import android.content.Context;
import android.content.res.Configuration;

/** iOS-like colour palettes. */
final class KbTheme {
    final boolean dark;
    final int bg, key, func, text, shadow, accent, accentText, shiftOn, shiftOnText,
            divider, bubble, stripPress, altSel;

    private KbTheme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF2B2B2D;
            key = 0xFF6C6C70;
            func = 0xFF46464A;
            text = 0xFFFFFFFF;
            shadow = 0xFF1A1A1C;
            accent = 0xFF0A84FF;
            accentText = 0xFFFFFFFF;
            shiftOn = 0xFFF2F2F7;
            shiftOnText = 0xFF000000;
            divider = 0x40FFFFFF;
            bubble = 0xFF6C6C70;
            stripPress = 0x26FFFFFF;
            altSel = 0xFF0A84FF;
        } else {
            bg = 0xFFD1D4DA;
            key = 0xFFFFFFFF;
            func = 0xFFABB0BA;
            text = 0xFF000000;
            shadow = 0xFF898A8E;
            accent = 0xFF007AFF;
            accentText = 0xFFFFFFFF;
            shiftOn = 0xFFFFFFFF;
            shiftOnText = 0xFF000000;
            divider = 0x40000000;
            bubble = 0xFFFFFFFF;
            stripPress = 0x1F000000;
            altSel = 0xFF007AFF;
        }
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

    static int lerp(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
