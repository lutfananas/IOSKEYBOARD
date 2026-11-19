package com.ioskeyboard.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String THEME = "theme", HAPTIC = "haptic", SOUND = "sound", AUTOCAP = "autocap",
            DOUBLE_SPACE = "dblspace", SUGGEST = "suggest", HEIGHT = "height", RECENT = "emoji_recent",
            BUBBLE = "bubble", BOTTOM = "bottom_pad", GLASS = "glass_transp";

    final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    int theme() { return sp.getInt(THEME, 0); }
    boolean haptic() { return sp.getBoolean(HAPTIC, true); }
    boolean sound() { return sp.getBoolean(SOUND, false); }
    boolean autoCap() { return sp.getBoolean(AUTOCAP, true); }
    boolean doubleSpace() { return sp.getBoolean(DOUBLE_SPACE, true); }
    boolean suggest() { return sp.getBoolean(SUGGEST, true); }
    boolean bubble() { return sp.getBoolean(BUBBLE, true); }
    /** keyboard height in percent, 60..130 */
    int height() { return sp.getInt(HEIGHT + "2", 88); }
    /** gap under the space row in dp, 0..48 */
    int bottom() { return sp.getInt(BOTTOM, 14); }
    /** background transparency in percent, 0..100 */
    int glass() { return sp.getInt(GLASS, 70); }

    String recent() { return sp.getString(RECENT, ""); }
    void setRecent(String s) { sp.edit().putString(RECENT, s).apply(); }
}
