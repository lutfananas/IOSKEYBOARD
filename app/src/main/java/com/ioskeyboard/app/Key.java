package com.ioskeyboard.app;

import android.graphics.RectF;

import java.util.Locale;

/** One key on the keyboard. Geometry is filled in by {@link KeyboardView}. */
final class Key {
    static final int CHAR = 0, SHIFT = 1, DELETE = 2, MODE_NUM = 3, MODE_SYM = 4,
            MODE_ABC = 5, EMOJI = 6, SPACE = 7, RETURN = 8;

    final int code;
    final String label;
    final String upper;
    String[] alts;
    String[] altsUpper;

    final RectF rect = new RectF();
    /** Horizontal touch zone (extends into the gaps around the key). */
    float hitL, hitR;

    boolean pressed;
    long releaseAt;

    Key(int code, String label) {
        this.code = code;
        this.label = label;
        this.upper = label == null ? null : label.toUpperCase(Locale.ROOT);
    }

    boolean isFunc() {
        return code != CHAR && code != SPACE;
    }

    boolean isMode() {
        return code == MODE_NUM || code == MODE_SYM || code == MODE_ABC;
    }
}
