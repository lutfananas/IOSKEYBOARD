package com.ioskeyboard.app;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Light, iOS-like key haptics and click sounds. */
final class Feedback {
    static final int KEY = 0, DELETE = 1, SPACE = 2, RETURN = 3;

    private final Vibrator vib;
    private final AudioManager am;
    private final VibrationEffect fx;
    boolean haptic = true, sound = false;

    Feedback(Context c) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        }
        vib = (v != null && v.hasVibrator()) ? v : null;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);

        VibrationEffect e = null;
        if (vib != null) {
            if (Build.VERSION.SDK_INT >= 30
                    && vib.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                e = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f).compose();
            } else if (Build.VERSION.SDK_INT >= 29) {
                e = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            } else {
                e = VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE);
            }
        }
        fx = e;
    }

    void play(int type) {
        if (haptic && vib != null && fx != null) {
            try { vib.vibrate(fx); } catch (Throwable ignored) { }
        }
        if (sound && am != null) {
            int s;
            switch (type) {
                case DELETE: s = AudioManager.FX_KEYPRESS_DELETE; break;
                case SPACE: s = AudioManager.FX_KEYPRESS_SPACEBAR; break;
                case RETURN: s = AudioManager.FX_KEYPRESS_RETURN; break;
                default: s = AudioManager.FX_KEYPRESS_STANDARD;
            }
            am.playSoundEffect(s, 0.5f);
        }
    }
}
