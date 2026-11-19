package com.ioskeyboard.app;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Locale;

public final class IosKeyboardService extends InputMethodService {
    private static final String[] DEFAULT_EMOJI = {"😂", "❤️", "🥲", "🔥", "🙏", "😍", "👍", "😭"};

    private FrameLayout root;
    private KeyboardView kv;
    private EmojiPanel emoji;
    private Prefs prefs;
    private Feedback fb;
    private Suggest suggest;
    private KbTheme theme;

    private EditorInfo info;
    private boolean noLearn, suggestOn, autoCapOn, dblSpaceOn, capField, emailOrUrl;

    /** Local mirror of the text before the cursor – avoids blocking IPC on every key press. */
    private final StringBuilder before = new StringBuilder();
    private int selStart = -1, selEnd = -1;
    private final int[] expected = new int[16];
    private int expectedPos;

    private long lastSpaceAt;
    private boolean lastWasSpace;

    // suggestions
    private final String[] sugLabel = new String[3];
    private final String[] sugValue = new String[3];
    private int sugCount;
    private String curWord = "";
    private final ArrayList<String> candTmp = new ArrayList<>();

    private String[] stripEmoji = DEFAULT_EMOJI;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        fb = new Feedback(this);
        suggest = new Suggest(this);
        loadRecent();
    }

    @Override
    public View onCreateInputView() {
        root = new FrameLayout(this);
        kv = new KeyboardView(this);
        emoji = new EmojiPanel(this);
        emoji.setVisibility(View.GONE);
        root.addView(kv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(emoji, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int b;
            if (Build.VERSION.SDK_INT >= 30) {
                b = ins.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                b = ins.getSystemWindowInsetBottom();
            }
            v.setPadding(0, 0, 0, b);
            return ins;
        });
        applyPrefs();
        return root;
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // iOS never switches to the extract-text UI
    }

    private void applyPrefs() {
        theme = KbTheme.of(this, prefs.theme());
        fb.haptic = prefs.haptic();
        fb.sound = prefs.sound();
        autoCapOn = prefs.autoCap();
        dblSpaceOn = prefs.doubleSpace();
        if (kv != null) {
            kv.setTheme(theme);
            kv.setHeightScale(prefs.height() / 100f);
            kv.setBubbles(prefs.bubble());
            emoji.applyTheme(theme);
            root.setBackgroundColor(theme.bg);
        }
        Window w = getWindow() != null ? getWindow().getWindow() : null;
        if (w != null) {
            w.setNavigationBarColor(theme.bg);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) c.setSystemBarsAppearance(theme.dark ? 0
                                : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            } else {
                View d = w.getDecorView();
                int f = d.getSystemUiVisibility();
                f = theme.dark ? f & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        : f | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                d.setSystemUiVisibility(f);
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        info = ei;
        applyPrefs();

        int cls = ei.inputType & InputType.TYPE_MASK_CLASS;
        int var = ei.inputType & InputType.TYPE_MASK_VARIATION;
        boolean numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;
        boolean password = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        emailOrUrl = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_URI);
        noLearn = password || (ei.imeOptions & 0x1000000 /* IME_FLAG_NO_PERSONALIZED_LEARNING */) != 0;
        suggestOn = prefs.suggest() && !password && !numeric && cls == InputType.TYPE_CLASS_TEXT;
        capField = cls == InputType.TYPE_CLASS_TEXT && !password && !emailOrUrl;

        kv.reset(numeric, returnLabel(ei), returnAccent(ei));
        showEmoji(false);

        selStart = ei.initialSelStart;
        selEnd = ei.initialSelEnd;
        expectedPos = 0;
        for (int i = 0; i < expected.length; i++) expected[i] = -2;
        lastWasSpace = false;
        resync();
        refresh();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (kv != null) kv.cancelTouches();
        suggest.saveAsync();
    }

    @Override
    public void onUpdateSelection(int oss, int ose, int nss, int nse, int cs, int ce) {
        super.onUpdateSelection(oss, ose, nss, nse, cs, ce);
        boolean ours = false;
        if (nss == nse) {
            for (int e : expected) if (e == nss) { ours = true; break; }
        }
        selStart = nss;
        selEnd = nse;
        if (!ours) {
            lastWasSpace = false;
            resync();
            refresh();
        }
    }

    // ------------------------------------------------------------------ text model

    private void resync() {
        before.setLength(0);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence cs = ic.getTextBeforeCursor(128, 0);
        if (cs != null) before.append(cs);
    }

    private void expect(int pos) {
        expected[expectedPos] = pos;
        expectedPos = (expectedPos + 1) % expected.length;
    }

    private void commit(String t) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(t, 1);
        before.append(t);
        if (before.length() > 400) before.delete(0, before.length() - 200);
        if (selStart >= 0) {
            int p = Math.min(selStart, selEnd) + t.length();
            selStart = selEnd = p;
            expect(p);
        }
    }

    /** Deletes n UTF-16 chars before the cursor (n comes from the local mirror). */
    private void deleteBefore(int n) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || n <= 0) return;
        ic.deleteSurroundingText(n, 0);
        before.setLength(Math.max(0, before.length() - n));
        if (selStart >= 0) {
            selStart = selEnd = Math.max(0, selStart - n);
            expect(selStart);
        }
    }

    // ------------------------------------------------------------------ actions from the view

    void onText(String s) {
        if (s.length() == 1 && !Character.isLetterOrDigit(s.charAt(0)) && s.charAt(0) != '\'') {
            learnCurrent();
            // iOS: "word ." -> "word." when punctuation follows an auto-inserted space
            if (lastWasSpace && (s.equals(".") || s.equals(",") || s.equals("?") || s.equals("!"))
                    && before.length() >= 2 && before.charAt(before.length() - 1) == ' '
                    && Character.isLetterOrDigit(before.charAt(before.length() - 2))
                    && SystemClock.uptimeMillis() - lastSpaceAt < 3000 && pickedSuggestion) {
                deleteBefore(1);
                commit(s + " ");
                pickedSuggestion = false;
                lastWasSpace = false;
                refresh();
                return;
            }
        }
        pickedSuggestion = false;
        lastWasSpace = false;
        commit(s);
        refresh();
    }

    private boolean pickedSuggestion;

    void onSpace() {
        long now = SystemClock.uptimeMillis();
        int n = before.length();
        if (dblSpaceOn && lastWasSpace && now - lastSpaceAt < 700 && n >= 2
                && before.charAt(n - 1) == ' ' && Character.isLetterOrDigit(before.charAt(n - 2))) {
            deleteBefore(1);
            commit(". ");
            lastWasSpace = false;
        } else {
            learnCurrent();
            commit(" ");
            lastWasSpace = true;
            lastSpaceAt = now;
        }
        pickedSuggestion = false;
        kv.afterSeparator();
        refresh();
    }

    void onReturn() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        learnCurrent();
        lastWasSpace = false;
        int action = info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        boolean multi = info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else if (multi) {
            commit("\n");
        } else {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
        refresh();
    }

    void onDelete(boolean word) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        lastWasSpace = false;
        pickedSuggestion = false;
        if (selStart >= 0 && selStart != selEnd) {
            ic.commitText("", 1);
            selEnd = selStart = Math.min(selStart, selEnd);
            expect(selStart);
            refresh();
            return;
        }
        if (before.length() < 32 && selStart > before.length()) resync();
        int n = before.length();
        if (n == 0) {
            // nothing known before the cursor: let the app handle a real backspace
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
            return;
        }
        int del;
        if (word) {
            int i = n;
            while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
            while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))) i--;
            del = Math.max(1, n - i);
        } else {
            del = lastGraphemeLength();
        }
        deleteBefore(del);
        refresh();
    }

    private int lastGraphemeLength() {
        int n = before.length();
        if (n == 0) return 0;
        try {
            android.icu.text.BreakIterator bi = android.icu.text.BreakIterator.getCharacterInstance();
            int from = Math.max(0, n - 40);
            bi.setText(before.substring(from));
            int end = bi.last();
            int prev = bi.previous();
            if (prev == android.icu.text.BreakIterator.DONE) return Character.charCount(before.codePointBefore(n));
            return end - prev;
        } catch (Throwable t) {
            return Character.charCount(before.codePointBefore(n));
        }
    }

    void moveCursor(int steps) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int code = steps < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        for (int i = Math.abs(steps); i > 0; i--) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        }
    }

    void onSuggestion(int idx) {
        if (idx < 0 || idx > 2 || sugValue[idx] == null) return;
        String w = sugValue[idx];
        if (!w.equals(curWord)) {
            deleteBefore(curWord.length());
            commit(w);
        }
        if (!noLearn) suggest.learn(w);
        commit(" ");
        lastWasSpace = true;
        pickedSuggestion = true;
        lastSpaceAt = SystemClock.uptimeMillis();
        refresh();
    }

    void onEmoji(String e) {
        lastWasSpace = false;
        commit(e);
        addRecent(e);
        refresh();
    }

    void showEmoji(boolean show) {
        if (kv == null) return;
        if (show) {
            kv.cancelTouches();
            emoji.getLayoutParams().height = kv.desiredHeight();
            emoji.open();
            emoji.setVisibility(View.VISIBLE);
            kv.setVisibility(View.INVISIBLE);
        } else {
            emoji.setVisibility(View.GONE);
            kv.setVisibility(View.VISIBLE);
            refresh();
        }
    }

    void showImePicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    void feedback(int type) {
        fb.play(type);
    }

    void refreshShift() {
        if (kv == null) return;
        kv.setAutoShift(shouldAutoCap());
    }

    // ------------------------------------------------------------------ state

    private void refresh() {
        if (kv == null) return;
        updateSuggestions();
        kv.setAutoShift(shouldAutoCap());
        kv.invalidate();
    }

    private boolean shouldAutoCap() {
        if (!autoCapOn || !capField || info == null) return false;
        if ((info.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) return true;
        int n = before.length();
        if (n == 0) return true;
        int i = n - 1;
        char c = before.charAt(i);
        if (c == '\n') return true;
        if (c != ' ') return false;
        while (i >= 0 && before.charAt(i) == ' ') i--;
        if (i < 0) return true;
        char p = before.charAt(i);
        return p == '.' || p == '!' || p == '?' || p == '\n';
    }

    private void updateSuggestions() {
        sugCount = 0;
        sugLabel[0] = sugLabel[1] = sugLabel[2] = null;
        sugValue[0] = sugValue[1] = sugValue[2] = null;
        int n = before.length(), i = n;
        while (i > 0) {
            char c = before.charAt(i - 1);
            if (Character.isLetter(c) || c == '\'' || c == '-') i--;
            else break;
        }
        curWord = before.substring(i);
        if (!suggestOn || curWord.isEmpty() || selStart != selEnd) return;

        candTmp.clear();
        boolean known = suggest.lookup(curWord, candTmp);
        String a = candTmp.size() > 0 ? candTmp.get(0) : null;
        String b = candTmp.size() > 1 ? candTmp.get(1) : null;
        if (known) {
            set(0, b, b);
            set(1, curWord, curWord);
            set(2, a, a);
        } else {
            set(0, "“" + curWord + "”", curWord);
            set(1, a, a);
            set(2, b, b);
        }
        sugCount = 3;
    }

    private void set(int i, String label, String value) {
        sugLabel[i] = label;
        sugValue[i] = value;
    }

    private void learnCurrent() {
        if (!noLearn && suggestOn && curWord.length() >= 2) suggest.learn(curWord);
    }

    int suggestionCount() {
        return sugCount;
    }

    String[] suggestionLabels() {
        return sugLabel;
    }

    String[] stripEmoji() {
        return stripEmoji;
    }

    // ------------------------------------------------------------------ recent emoji

    private final ArrayList<String> recent = new ArrayList<>();

    private void loadRecent() {
        recent.clear();
        String s = prefs.recent();
        if (!s.isEmpty()) for (String e : s.split("\t")) if (!e.isEmpty()) recent.add(e);
        rebuildStrip();
    }

    private void addRecent(String e) {
        recent.remove(e);
        recent.add(0, e);
        while (recent.size() > 40) recent.remove(recent.size() - 1);
        prefs.setRecent(String.join("\t", recent));
        rebuildStrip();
    }

    ArrayList<String> recentEmoji() {
        return recent;
    }

    private void rebuildStrip() {
        String[] s = new String[KeyboardView.STRIP_EMOJI_COUNT];
        int k = 0;
        for (String e : recent) {
            if (k == s.length) break;
            s[k++] = e;
        }
        for (String e : DEFAULT_EMOJI) {
            if (k == s.length) break;
            boolean dup = false;
            for (int j = 0; j < k; j++) if (s[j].equals(e)) { dup = true; break; }
            if (!dup) s[k++] = e;
        }
        stripEmoji = s;
    }

    private static String returnLabel(EditorInfo ei) {
        if ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "return";
        switch (ei.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO: return "go";
            case EditorInfo.IME_ACTION_SEARCH: return "search";
            case EditorInfo.IME_ACTION_SEND: return "send";
            case EditorInfo.IME_ACTION_NEXT: return "next";
            case EditorInfo.IME_ACTION_DONE: return "done";
            case EditorInfo.IME_ACTION_PREVIOUS: return "prev";
            default: return "return";
        }
    }

    private static boolean returnAccent(EditorInfo ei) {
        if ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false;
        int a = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        return a == EditorInfo.IME_ACTION_GO || a == EditorInfo.IME_ACTION_SEARCH
                || a == EditorInfo.IME_ACTION_SEND || a == EditorInfo.IME_ACTION_DONE;
    }

    static String matchCase(String typed, String word) {
        if (typed.isEmpty() || word.isEmpty()) return word;
        if (typed.length() > 1 && typed.equals(typed.toUpperCase(Locale.ROOT))
                && !typed.equals(typed.toLowerCase(Locale.ROOT))) {
            return word.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(typed.charAt(0))) {
            return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return word;
    }

    KbTheme theme() {
        return theme;
    }
}
