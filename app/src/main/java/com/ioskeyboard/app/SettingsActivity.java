package com.ioskeyboard.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public final class SettingsActivity extends Activity {
    private Prefs prefs;
    private TextView stEnable, stSelect, heightLabel;
    private Button btnSelect;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        SharedPreferences sp = prefs.sp;

        stEnable = findViewById(R.id.stEnable);
        stSelect = findViewById(R.id.stSelect);
        btnSelect = findViewById(R.id.btnSelect);
        heightLabel = findViewById(R.id.heightLabel);

        findViewById(R.id.btnEnable).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        btnSelect.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });

        RadioGroup theme = findViewById(R.id.theme);
        int t = prefs.theme();
        theme.check(t == 1 ? R.id.thLight : t == 2 ? R.id.thDark : R.id.thAuto);
        theme.setOnCheckedChangeListener((g, id) -> sp.edit().putInt(Prefs.THEME,
                id == R.id.thLight ? 1 : id == R.id.thDark ? 2 : 0).apply());

        SeekBar height = findViewById(R.id.height);
        height.setProgress(prefs.height() - 80);
        heightLabel.setText("Tinggi keyboard: " + prefs.height() + "%");
        height.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                heightLabel.setText("Tinggi keyboard: " + (p + 80) + "%");
                sp.edit().putInt(Prefs.HEIGHT, p + 80).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        bind(R.id.swBubble, Prefs.BUBBLE, prefs.bubble());
        bind(R.id.swHaptic, Prefs.HAPTIC, prefs.haptic());
        bind(R.id.swSound, Prefs.SOUND, prefs.sound());
        bind(R.id.swCaps, Prefs.AUTOCAP, prefs.autoCap());
        bind(R.id.swDot, Prefs.DOUBLE_SPACE, prefs.doubleSpace());
        bind(R.id.swSuggest, Prefs.SUGGEST, prefs.suggest());
    }

    private void bind(int id, String key, boolean value) {
        Switch s = findViewById(id);
        s.setChecked(value);
        s.setOnCheckedChangeListener((CompoundButton b, boolean c) -> prefs.sp.edit().putBoolean(key, c).apply());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateStatus();
    }

    private void updateStatus() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled = false, selected = false;
        if (imm != null) {
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
                if (i.getPackageName().equals(getPackageName())) {
                    enabled = true;
                    break;
                }
            }
            if (Build.VERSION.SDK_INT >= 34) {
                InputMethodInfo cur = imm.getCurrentInputMethodInfo();
                selected = cur != null && cur.getPackageName().equals(getPackageName());
            } else {
                String cur = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                selected = cur != null && cur.startsWith(getPackageName() + "/");
            }
        }
        stEnable.setText(enabled ? "✅ Sudah aktif" : "Belum aktif — nyalakan \"iOS Keyboard\" di daftar.");
        stSelect.setText(selected ? "✅ Sudah jadi keyboard default" : "Belum dipilih sebagai default.");
        btnSelect.setEnabled(enabled);
        btnSelect.setAlpha(enabled ? 1f : 0.4f);
    }
}
