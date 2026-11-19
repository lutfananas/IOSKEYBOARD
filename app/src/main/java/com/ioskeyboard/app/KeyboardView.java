package com.ioskeyboard.app;

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Locale;

/**
 * The whole keyboard (suggestion / emoji strip + keys + popups) is drawn by this single
 * hardware-accelerated view. Nothing is inflated, nothing is allocated while drawing, so
 * typing stays at full frame rate.
 */
@SuppressLint("ViewConstructor")
public final class KeyboardView extends View {
    static final int L_LETTERS = 0, L_NUMBERS = 1, L_SYMBOLS = 2;
    static final int SHIFT_OFF = 0, SHIFT_ON = 1, SHIFT_LOCK = 2;

    private static final long LONG_PRESS_MS = 380;
    private static final long SPACE_HOLD_MS = 300;
    private static final long DOUBLE_TAP_MS = 320;
    private static final long FADE_MS = 130;

    private final IosKeyboardService ime;
    private final float dp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private KbTheme th;

    private final Key[][][] layouts = new Key[3][][];
    private int layout = L_LETTERS;
    private int shift = SHIFT_OFF;
    private long lastShiftTap;
    private boolean numericField;
    private boolean bubbles = true;

    // geometry
    private float stripH, rowPitch, vGap, hGap, side, radius, bottomPad, keyH;
    private float heightScale = 1f;
    private float bottomDp = 14f;
    private int width;

    // return key
    private String returnLabel = "return";
    private boolean returnAccent;

    // paints
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint funcText = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bigText = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint stripText = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint emojiText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final Rect bounds = new Rect();
    private final Path path = new Path();
    private final Path icon = new Path();
    private float lowerOff, upperOff, funcOff, bigLowerOff, bigUpperOff, stripOff, emojiOff;

    // touch tracking
    private static final class Ptr {
        boolean active;
        Key key;
        float downX, downY, lastCursorX, curX;
        boolean committed, fromMode, moved, cursor, alt;
        int strip = -1;
    }

    private final Ptr[] ptrs = new Ptr[10];
    private Ptr lpPtr;
    private Ptr delPtr;
    private int delCount;
    private Key previewKey;

    // long-press alternates popup
    private Ptr altPtr;
    private Key altKey;
    private String[] altList;
    private int altSel;
    private final RectF altBox = new RectF();
    private float altCell;

    // space-bar trackpad
    private boolean cursorMode;
    private long cursorChangeAt;

    // strip
    private int stripPressed = -1;
    static final int STRIP_EMOJI_COUNT = 8;

    private static final HashMap<String, String[]> ALTS = new HashMap<>();

    static {
        put("a", "a à á â ä æ ã å ā");
        put("c", "c ç ć č");
        put("e", "e è é ê ë ē ė ę");
        put("i", "i ì į ī í ï î");
        put("l", "l ł");
        put("n", "n ñ ń");
        put("o", "o õ ō ø œ ó ò ö ô");
        put("s", "s ß ś š");
        put("u", "u ū ú ù ü û");
        put("y", "y ÿ ý");
        put("z", "z ž ź ż");
        put("0", "0 °");
        put("-", "- – — •");
        put("/", "/ \\");
        put("$", "$ Rp € £ ¥ ₩ ¢ ₽");
        put("&", "& §");
        put("\"", "\" „ “ ” « »");
        put(".", ". …");
        put("?", "? ¿");
        put("!", "! ¡");
        put("'", "' ` ‘ ’");
        put("%", "% ‰");
        put("=", "= ≠ ≈");
    }

    private static void put(String k, String v) {
        ALTS.put(k, v.split(" "));
    }

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            Ptr p = lpPtr;
            if (p != null && p.active && p.strip == STRIP_EMOJI_COUNT) {
                // hold the chevron: switch keyboard (replaces the globe key)
                p.strip = -1;
                p.committed = true;
                stripPressed = -1;
                ime.feedback(Feedback.KEY);
                ime.showImePicker();
                invalidate();
                return;
            }
            if (p == null || !p.active || p.key == null || p.committed) return;
            Key k = p.key;
            if (k.code == Key.SPACE) {
                startCursor(p);
            } else if (k.code == Key.EMOJI) {
                p.committed = true;
                k.pressed = false;
                k.releaseAt = SystemClock.uptimeMillis();
                ime.showImePicker();
                invalidate();
            } else if (k.code == Key.CHAR && k.alts != null) {
                openAlts(p, k);
            }
        }
    };

    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            Ptr p = delPtr;
            if (p == null || !p.active) return;
            delCount++;
            ime.onDelete(delCount > 20);
            ime.feedback(Feedback.DELETE);
            h.postDelayed(this, delCount > 20 ? 130 : 55);
        }
    };

    public KeyboardView(IosKeyboardService ime) {
        super(ime);
        this.ime = ime;
        dp = getResources().getDisplayMetrics().density;
        for (int i = 0; i < ptrs.length; i++) ptrs[i] = new Ptr();

        Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);
        text.setTypeface(reg);
        text.setTextAlign(Paint.Align.CENTER);
        bigText.setTypeface(reg);
        bigText.setTextAlign(Paint.Align.CENTER);
        funcText.setTypeface(reg);
        funcText.setTextAlign(Paint.Align.CENTER);
        stripText.setTypeface(reg);
        stripText.setTextAlign(Paint.Align.CENTER);
        emojiText.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        layouts[L_LETTERS] = new Key[][]{
                chars("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                chars("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                wrap(new Key(Key.SHIFT, null), chars("z", "x", "c", "v", "b", "n", "m"), new Key(Key.DELETE, null)),
                bottomRow(Key.MODE_NUM, "123")
        };
        layouts[L_NUMBERS] = new Key[][]{
                chars("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                chars("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
                wrap(new Key(Key.MODE_SYM, "#+="), chars(".", ",", "?", "!", "'"), new Key(Key.DELETE, null)),
                bottomRow(Key.MODE_ABC, "ABC")
        };
        layouts[L_SYMBOLS] = new Key[][]{
                chars("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
                chars("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
                wrap(new Key(Key.MODE_NUM, "123"), chars(".", ",", "?", "!", "'"), new Key(Key.DELETE, null)),
                bottomRow(Key.MODE_ABC, "ABC")
        };
        setTheme(KbTheme.of(ime, 0));
    }

    private static Key[] chars(String... s) {
        Key[] r = new Key[s.length];
        for (int i = 0; i < s.length; i++) {
            Key k = new Key(Key.CHAR, s[i]);
            String[] a = ALTS.get(s[i]);
            if (a != null) {
                k.alts = a;
                k.altsUpper = new String[a.length];
                for (int j = 0; j < a.length; j++) k.altsUpper[j] = a[j].toUpperCase(Locale.ROOT);
            }
            r[i] = k;
        }
        return r;
    }

    private static Key[] wrap(Key first, Key[] mid, Key last) {
        Key[] r = new Key[mid.length + 2];
        r[0] = first;
        System.arraycopy(mid, 0, r, 1, mid.length);
        r[r.length - 1] = last;
        return r;
    }

    private static Key[] bottomRow(int mode, String label) {
        return new Key[]{new Key(mode, label), new Key(Key.SPACE, "space"), new Key(Key.RETURN, "Return")};
    }

    // ------------------------------------------------------------------ public api

    void setTheme(KbTheme t) {
        th = t;
        rimPaint.setColor(t.rim);
        invalidate();
    }

    void setHeightScale(float s) {
        if (s != heightScale) {
            heightScale = s;
            requestLayout();
        }
    }

    void setBottomPad(float d) {
        if (d != bottomDp) {
            bottomDp = d;
            requestLayout();
        }
    }

    void setBubbles(boolean b) {
        bubbles = b;
    }

    void reset(boolean numeric, String retLabel, boolean accent) {
        cancelTouches();
        numericField = numeric;
        layout = numeric ? L_NUMBERS : L_LETTERS;
        returnLabel = retLabel;
        returnAccent = accent;
        if (!numeric) shift = SHIFT_OFF;
        invalidate();
    }

    int layout() {
        return layout;
    }

    boolean isShifted() {
        return layout == L_LETTERS && shift != SHIFT_OFF;
    }

    /** Called by the service after text changes: applies auto-capitalisation. */
    void setAutoShift(boolean on) {
        if (shift == SHIFT_LOCK || layout != L_LETTERS) return;
        int ns = on ? SHIFT_ON : SHIFT_OFF;
        if (ns != shift) {
            shift = ns;
            invalidate();
        }
    }

    /** iOS returns to letters after a space / apostrophe typed from the number pages. */
    void afterSeparator() {
        if (layout != L_LETTERS && !numericField) {
            layout = L_LETTERS;
            invalidate();
        }
    }

    int desiredHeight() {
        computeMetrics();
        return (int) (stripH + rowPitch * 4 + bottomPad);
    }

    void cancelTouches() {
        h.removeCallbacks(longPress);
        h.removeCallbacks(repeatDelete);
        for (Ptr p : ptrs) {
            if (p.key != null) p.key.pressed = false;
            p.active = false;
            p.key = null;
        }
        lpPtr = delPtr = altPtr = null;
        altKey = null;
        previewKey = null;
        cursorMode = false;
        stripPressed = -1;
        invalidate();
    }

    // ------------------------------------------------------------------ geometry

    private void computeMetrics() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (land) {
            stripH = 38 * dp;
            rowPitch = 41 * dp * heightScale;
            vGap = 6 * dp * heightScale;
            radius = 9 * dp;
        } else {
            stripH = 46 * dp;
            rowPitch = 55 * dp * heightScale;
            vGap = 11 * dp * heightScale;
            radius = 11 * dp;
        }
        hGap = 6 * dp;
        side = 3.5f * dp;
        bottomPad = (land ? Math.min(bottomDp, 8f) : bottomDp) * dp;
        keyH = rowPitch - vGap;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, desiredHeight());
    }

    @Override
    protected void onSizeChanged(int w, int hh, int ow, int oh) {
        width = w;
        computeMetrics();
        for (Key[][] l : layouts) layoutRows(l);
        float letter = keyH * 0.56f;
        text.setTextSize(letter);
        bigText.setTextSize(keyH * 0.9f);
        funcText.setTextSize(Math.min(keyH * 0.37f, 16.5f * dp));
        stripText.setTextSize(Math.min(stripH * 0.38f, 17 * dp));
        emojiText.setTextSize(stripH * 0.5f);
        stroke.setStrokeWidth(1.6f * dp);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(0.7f * dp);

        text.getTextBounds("x", 0, 1, bounds);
        lowerOff = bounds.height() / 2f;
        text.getTextBounds("H", 0, 1, bounds);
        upperOff = bounds.height() / 2f;
        bigText.getTextBounds("x", 0, 1, bounds);
        bigLowerOff = bounds.height() / 2f;
        bigText.getTextBounds("H", 0, 1, bounds);
        bigUpperOff = bounds.height() / 2f;
        funcText.getTextBounds("x", 0, 1, bounds);
        funcOff = bounds.height() / 2f;
        stripText.getTextBounds("x", 0, 1, bounds);
        stripOff = bounds.height() / 2f;
        Paint.FontMetrics fm = emojiText.getFontMetrics();
        emojiOff = -(fm.ascent + fm.descent) / 2f;
    }

    private void layoutRows(Key[][] rows) {
        float total = width - side * 2;
        float u = (total - 9 * hGap) / 10f;
        float mod = 1.25f * u + 0.5f * hGap;
        float bigGap = 0.25f * u + hGap;
        for (int r = 0; r < rows.length; r++) {
            Key[] row = rows[r];
            float top = stripH + r * rowPitch + vGap / 2f;
            float bot = top + keyH;
            int n = row.length;
            if (r < 2) {
                float span = n * u + (n - 1) * hGap;
                float x = side + (total - span) / 2f;
                for (Key k : row) {
                    k.rect.set(x, top, x + u, bot);
                    x += u + hGap;
                }
            } else if (r == 2) {
                row[0].rect.set(side, top, side + mod, bot);
                row[n - 1].rect.set(side + total - mod, top, side + total, bot);
                int m = n - 2;
                float l = side + mod + bigGap, rr = side + total - mod - bigGap;
                float kw;
                float x;
                if (m == 7) {
                    kw = u;
                    float span = 7 * u + 6 * hGap;
                    x = side + (total - span) / 2f;
                } else {
                    kw = ((rr - l) - (m - 1) * hGap) / m;
                    x = l;
                }
                for (int i = 1; i <= m; i++) {
                    row[i].rect.set(x, top, x + kw, bot);
                    x += kw + hGap;
                }
            } else {
                // iOS 26: [123]  [      space      ]  [Return ⏎]
                float edge = 2.5f * u + 1.5f * hGap;
                row[0].rect.set(side, top, side + edge, bot);
                row[1].rect.set(side + edge + hGap, top, side + total - edge - hGap, bot);
                row[2].rect.set(side + total - edge, top, side + total, bot);
            }
            // touch zones: split gaps half-half, edges extend to the screen border
            for (int i = 0; i < n; i++) {
                Key k = row[i];
                k.hitL = i == 0 ? 0 : (row[i - 1].rect.right + k.rect.left) / 2f;
                k.hitR = i == n - 1 ? width : (k.rect.right + row[i + 1].rect.left) / 2f;
            }
        }
    }

    private Key hit(float x, float y) {
        Key[][] rows = layouts[layout];
        int r = (int) ((y - stripH) / rowPitch);
        if (r < 0) r = 0;
        if (r > 3) r = 3;
        Key[] row = rows[r];
        for (Key k : row) if (x >= k.hitL && x < k.hitR) return k;
        Key best = row[0];
        float bd = Float.MAX_VALUE;
        for (Key k : row) {
            float d = Math.abs(k.rect.centerX() - x);
            if (d < bd) {
                bd = d;
                best = k;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        switch (a) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                up(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelTouches();
                break;
        }
        return true;
    }

    private Ptr ptr(int id) {
        return id >= 0 && id < ptrs.length ? ptrs[id] : null;
    }

    private void down(int id, float x, float y) {
        Ptr p = ptr(id);
        if (p == null) return;
        if (altPtr != null || cursorMode) return; // one gesture owns the keyboard

        // fast typing roll-over: commit the key still held by another finger
        for (Ptr o : ptrs) {
            if (o != p && o.active && !o.committed && o.key != null && o.key.code == Key.CHAR) {
                commitChar(o.key, o.fromMode && o.moved);
                o.committed = true;
                o.key.pressed = false;
                if (previewKey == o.key) previewKey = null;
            }
        }

        p.active = true;
        p.committed = p.fromMode = p.moved = p.cursor = p.alt = false;
        p.downX = p.lastCursorX = p.curX = x;
        p.downY = y;
        p.strip = -1;
        p.key = null;

        if (y < stripH) {
            p.strip = stripCell(x);
            stripPressed = p.strip;
            if (p.strip == STRIP_EMOJI_COUNT) {
                lpPtr = p;
                h.removeCallbacks(longPress);
                h.postDelayed(longPress, LONG_PRESS_MS + 120);
            }
            invalidate();
            return;
        }

        Key k = hit(x, y);
        p.key = k;
        k.pressed = true;

        switch (k.code) {
            case Key.CHAR:
                ime.feedback(Feedback.KEY);
                previewKey = k;
                if (k.alts != null) scheduleLongPress(p);
                break;
            case Key.SHIFT:
                ime.feedback(Feedback.KEY);
                onShiftTap();
                break;
            case Key.DELETE:
                ime.feedback(Feedback.DELETE);
                ime.onDelete(false);
                delPtr = p;
                delCount = 0;
                h.removeCallbacks(repeatDelete);
                h.postDelayed(repeatDelete, 420);
                break;
            case Key.MODE_NUM:
            case Key.MODE_SYM:
            case Key.MODE_ABC:
                ime.feedback(Feedback.KEY);
                layout = k.code == Key.MODE_NUM ? L_NUMBERS : k.code == Key.MODE_SYM ? L_SYMBOLS : L_LETTERS;
                p.fromMode = true;
                if (layout == L_LETTERS) ime.refreshShift();
                // the same physical key exists on the new page – keep it highlighted
                k.pressed = false;
                Key nk = hit(x, y);
                nk.pressed = true;
                p.key = nk;
                break;
            case Key.SPACE:
                ime.feedback(Feedback.SPACE);
                scheduleLongPress(p);
                break;
            case Key.EMOJI:
                ime.feedback(Feedback.KEY);
                scheduleLongPress(p);
                break;
            case Key.RETURN:
                ime.feedback(Feedback.RETURN);
                break;
        }
        invalidate();
    }

    private void scheduleLongPress(Ptr p) {
        lpPtr = p;
        h.removeCallbacks(longPress);
        h.postDelayed(longPress, p.key != null && p.key.code == Key.SPACE ? SPACE_HOLD_MS : LONG_PRESS_MS);
    }

    private void move(int id, float x, float y) {
        Ptr p = ptr(id);
        if (p == null || !p.active || p.committed) return;
        p.curX = x;
        if (p.strip >= 0) {
            int c = y < stripH + 10 * dp ? stripCell(x) : -1;
            int np = c == p.strip ? p.strip : -1;
            if (np != stripPressed) {
                stripPressed = np;
                invalidate();
            }
            return;
        }
        Key k = p.key;
        if (k == null) return;

        if (p.alt) {
            int s = (int) ((x - altBox.left - 4 * dp) / altCell);
            if (s < 0) s = 0;
            if (s >= altList.length) s = altList.length - 1;
            if (s != altSel) {
                altSel = s;
                invalidate();
            }
            return;
        }
        if (p.cursor) {
            float step = 9 * dp;
            int steps = (int) ((x - p.lastCursorX) / step);
            if (steps != 0) {
                p.lastCursorX += steps * step;
                ime.moveCursor(steps);
            }
            return;
        }
        if (k.code == Key.SPACE) {
            if (Math.abs(x - p.downX) > 16 * dp) startCursor(p);
            return;
        }
        if (k.code == Key.CHAR || p.fromMode) {
            if (y < stripH - 6 * dp) return;
            Key nk = hit(x, y);
            if (nk != k && nk.code == Key.CHAR) {
                k.pressed = false;
                nk.pressed = true;
                p.key = nk;
                p.moved = true;
                previewKey = nk;
                h.removeCallbacks(longPress);
                if (nk.alts != null) scheduleLongPress(p);
                invalidate();
            }
        }
    }

    private void up(int id, float x, float y) {
        Ptr p = ptr(id);
        if (p == null || !p.active) return;
        p.active = false;
        if (lpPtr == p) {
            h.removeCallbacks(longPress);
            lpPtr = null;
        }
        if (delPtr == p) {
            h.removeCallbacks(repeatDelete);
            delPtr = null;
        }

        if (p.strip >= 0) {
            if (stripPressed == p.strip) onStripTap(p.strip);
            stripPressed = -1;
            p.strip = -1;
            invalidate();
            return;
        }

        Key k = p.key;
        p.key = null;
        if (k == null) return;
        k.pressed = false;
        k.releaseAt = SystemClock.uptimeMillis();
        if (previewKey == k) previewKey = null;

        if (p.alt) {
            String s = (shift != SHIFT_OFF && layout == L_LETTERS ? altKey.altsUpper : altKey.alts)[altSel];
            altPtr = null;
            altKey = null;
            ime.onText(s);
            afterCharCommitted(s);
        } else if (p.cursor) {
            cursorMode = false;
            cursorChangeAt = SystemClock.uptimeMillis();
        } else if (!p.committed) {
            switch (k.code) {
                case Key.CHAR:
                    commitChar(k, p.fromMode && p.moved);
                    break;
                case Key.SPACE:
                    ime.onSpace();
                    break;
                case Key.RETURN:
                    ime.onReturn();
                    break;
                case Key.EMOJI:
                    ime.showEmoji(true);
                    break;
            }
        }
        invalidate();
    }

    private void commitChar(Key k, boolean slidFromMode) {
        String s = (layout == L_LETTERS && shift != SHIFT_OFF) ? k.upper : k.label;
        ime.onText(s);
        afterCharCommitted(s);
        if (slidFromMode && layout != L_LETTERS && !numericField) {
            layout = L_LETTERS;
            ime.refreshShift();
        }
    }

    private void afterCharCommitted(String s) {
        if (layout == L_LETTERS && shift == SHIFT_ON) {
            shift = SHIFT_OFF;
            ime.refreshShift();
        }
        if ("'".equals(s)) afterSeparator();
    }

    private void onShiftTap() {
        long now = SystemClock.uptimeMillis();
        if (shift == SHIFT_LOCK) {
            shift = SHIFT_OFF;
        } else if (shift == SHIFT_ON && now - lastShiftTap < DOUBLE_TAP_MS) {
            shift = SHIFT_LOCK;
        } else {
            shift = shift == SHIFT_OFF ? SHIFT_ON : SHIFT_OFF;
        }
        lastShiftTap = now;
    }

    private void startCursor(Ptr p) {
        h.removeCallbacks(longPress);
        p.cursor = true;
        p.lastCursorX = p.curX;
        cursorMode = true;
        cursorChangeAt = SystemClock.uptimeMillis();
        ime.feedback(Feedback.KEY);
        invalidate();
    }

    private void openAlts(Ptr p, Key k) {
        p.alt = true;
        altPtr = p;
        altKey = k;
        altList = (shift != SHIFT_OFF && layout == L_LETTERS) ? k.altsUpper : k.alts;
        previewKey = null;
        altCell = Math.max(k.rect.width(), 30 * dp);
        float pad = 4 * dp;
        float w = altList.length * altCell + pad * 2;
        float l = k.rect.left - pad;
        if (l + w > width - side) l = width - side - w;
        if (l < side) l = side;
        float boxH = keyH * 1.12f;
        float top = Math.max(1 * dp, k.rect.top - boxH - 4 * dp);
        altBox.set(l, top, l + w, top + boxH);
        int s = (int) ((k.rect.centerX() - l - pad) / altCell);
        altSel = Math.max(0, Math.min(altList.length - 1, s));
        ime.feedback(Feedback.KEY);
        invalidate();
    }

    // ------------------------------------------------------------------ strip

    private int stripCount() {
        return STRIP_EMOJI_COUNT + 1;
    }

    private int stripCell(float x) {
        int n = stripCount();
        int c = (int) ((x - side) / ((width - side * 2) / (float) n));
        return Math.max(0, Math.min(n - 1, c));
    }

    private void onStripTap(int cell) {
        if (cell >= STRIP_EMOJI_COUNT) {
            ime.feedback(Feedback.KEY);
            ime.showEmoji(true);
        } else {
            String[] em = ime.stripEmoji();
            if (cell < em.length) {
                ime.feedback(Feedback.KEY);
                ime.onEmoji(em[cell]);
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        final long now = SystemClock.uptimeMillis();
        boolean animating = false;

        // background (blurred glass) is painted by the window / root view

        float ct = (now - cursorChangeAt) / 150f;
        if (ct < 1f) animating = true;
        else ct = 1f;
        float cursorAnim = cursorMode ? ct : 1f - ct;
        int labelAlpha = (int) (255 * (1f - cursorAnim));

        drawStrip(c, labelAlpha);

        Key[][] rows = layouts[layout];
        for (Key[] row : rows) {
            for (Key k : row) {
                float f = 0f;
                if (k.pressed) f = 1f;
                else {
                    float t = (now - k.releaseAt) / (float) FADE_MS;
                    if (t < 1f) {
                        f = 1f - t;
                        animating = true;
                    }
                }
                drawKey(c, k, f, labelAlpha, cursorAnim);
            }
        }

        if (previewKey != null && bubbles && !cursorMode && altPtr == null) drawBubble(c, previewKey);
        if (altPtr != null && altKey != null) drawAlts(c);

        if (animating) postInvalidateOnAnimation();
    }

    private void drawKey(Canvas c, Key k, float press, int labelAlpha, float cursorAnim) {
        RectF r = k.rect;
        int fg = th.text;
        boolean bare = k.code == Key.SHIFT || k.code == Key.DELETE || k.code == Key.RETURN || k.isMode();
        if (bare) {
            // iOS 26: function keys have no plate, only a soft highlight while touched
            if (k.code == Key.SHIFT && shift != SHIFT_OFF) {
                fill.setColor(th.shiftOn);
                c.drawRoundRect(r, radius, radius, fill);
                fg = th.shiftOnText;
            } else if (press > 0f) {
                fill.setColor(th.funcPressed);
                fill.setAlpha((int) (Color.alpha(th.funcPressed) * press));
                c.drawRoundRect(r, radius, radius, fill);
            }
        } else {
            int pressedCol = (k.code == Key.CHAR && bubbles) ? th.key : th.keyPressed;
            int col = KbTheme.lerp(th.key, pressedCol, press);
            if (cursorAnim > 0f) {
                int a = (int) (Color.alpha(col) * (1f - 0.55f * cursorAnim));
                col = (col & 0x00FFFFFF) | (a << 24);
            }
            fill.setColor(col);
            c.drawRoundRect(r, radius, radius, fill);
            c.drawRoundRect(r, radius, radius, rimPaint);
        }

        if (labelAlpha <= 0) return;
        float cx = r.centerX(), cy = r.centerY();
        switch (k.code) {
            case Key.CHAR: {
                boolean up = layout == L_LETTERS && shift != SHIFT_OFF;
                String s = up ? k.upper : k.label;
                boolean lowerLetter = !up && layout == L_LETTERS;
                text.setColor(fg);
                text.setAlpha(labelAlpha);
                c.drawText(s, cx, cy + (lowerLetter ? lowerOff : upperOff), text);
                break;
            }
            case Key.SHIFT:
                drawShift(c, cx, cy, keyH * 0.42f, fg, labelAlpha);
                break;
            case Key.DELETE:
                drawDelete(c, cx, cy, keyH * 0.4f, fg, labelAlpha);
                break;
            case Key.EMOJI:
                drawSmiley(c, cx, cy, keyH * 0.46f, fg, labelAlpha);
                break;
            case Key.RETURN: {
                funcText.setColor(fg);
                funcText.setAlpha(labelAlpha);
                boolean withIcon = "Return".equals(returnLabel);
                float tw = funcText.measureText(returnLabel);
                float is = funcText.getTextSize() * 0.62f;
                float gap = 5 * dp;
                float total = tw + (withIcon ? gap + is : 0);
                float tx = cx - total / 2f + tw / 2f;
                c.drawText(returnLabel, tx, cy + funcOff, funcText);
                if (withIcon) drawReturnIcon(c, cx - total / 2f + tw + gap, cy, is, fg, labelAlpha);
                break;
            }
            default:
                funcText.setColor(fg);
                funcText.setAlpha(labelAlpha);
                c.drawText(k.label, cx, cy + funcOff, funcText);
        }
    }

    private void drawStrip(Canvas c, int alpha) {
        if (alpha <= 0) return;
        float w = width - side * 2;
        int n = stripCount();
        float cw = w / n;
        float cy = stripH / 2f;
        if (stripPressed >= 0 && stripPressed < n) {
            fill.setColor(th.stripPress);
            float l = side + stripPressed * cw;
            tmp.set(l + 2 * dp, 5 * dp, l + cw - 2 * dp, stripH - 5 * dp);
            c.drawRoundRect(tmp, radius, radius, fill);
        }
        if (false) {
            String[] s = ime.suggestionLabels();
            stripText.setColor(th.text);
            stripText.setAlpha(alpha);
            fill.setColor(th.divider);
            for (int i = 0; i < 3; i++) {
                if (s[i] == null) continue;
                float x = side + cw * i + cw / 2f;
                stripText.setFakeBoldText(i == 1);
                String label = ellipsize(s[i], cw - 12 * dp);
                c.drawText(label, x, cy + stripOff, stripText);
                if (i < 2 && s[i + 1] != null && stripPressed != i && stripPressed != i + 1) {
                    float dx = side + cw * (i + 1);
                    c.drawRect(dx - 0.5f * dp, cy - stripH * 0.22f, dx + 0.5f * dp, cy + stripH * 0.22f, fill);
                }
            }
            stripText.setFakeBoldText(false);
        } else {
            String[] em = ime.stripEmoji();
            emojiText.setAlpha(alpha);
            for (int i = 0; i < STRIP_EMOJI_COUNT && i < em.length; i++) {
                c.drawText(em[i], side + cw * i + cw / 2f, cy + emojiOff, emojiText);
            }
            // chevron
            float x = side + cw * STRIP_EMOJI_COUNT + cw / 2f;
            float s = stripH * 0.13f;
            stroke.setColor(th.text);
            stroke.setAlpha(alpha);
            icon.reset();
            icon.moveTo(x - s, cy - s * 0.45f);
            icon.lineTo(x, cy + s * 0.55f);
            icon.lineTo(x + s, cy - s * 0.45f);
            c.drawPath(icon, stroke);
        }
    }

    private String ellipsize(String s, float max) {
        if (stripText.measureText(s) <= max) return s;
        int n = stripText.breakText(s, true, max - stripText.measureText("…"), null);
        return s.substring(0, Math.max(1, n)) + "…";
    }

    private void drawBubble(Canvas c, Key k) {
        RectF r = k.rect;
        float kw = r.width(), kh = r.height();
        float bw = Math.max(kw * 1.55f, kw + 18 * dp);
        float cx = r.centerX();
        float tl = cx - bw / 2f, tr = cx + bw / 2f;
        if (tl < 1 * dp) {
            tr += 1 * dp - tl;
            tl = 1 * dp;
        }
        if (tr > width - 1 * dp) {
            tl -= tr - (width - 1 * dp);
            tr = width - 1 * dp;
        }
        float bt = Math.max(0, r.top - kh * 1.18f);
        float bb = r.top - kh * 0.12f;
        float neckEnd = r.top + kh * 0.22f;
        float rad = radius * 1.35f;
        float kr = radius;

        path.reset();
        path.moveTo(tl, bt + rad);
        path.quadTo(tl, bt, tl + rad, bt);
        path.lineTo(tr - rad, bt);
        path.quadTo(tr, bt, tr, bt + rad);
        path.lineTo(tr, bb);
        float mid = (bb + neckEnd) / 2f;
        path.cubicTo(tr, mid, r.right, mid, r.right, neckEnd);
        path.lineTo(r.right, r.bottom - kr);
        path.quadTo(r.right, r.bottom, r.right - kr, r.bottom);
        path.lineTo(r.left + kr, r.bottom);
        path.quadTo(r.left, r.bottom, r.left, r.bottom - kr);
        path.lineTo(r.left, neckEnd);
        path.cubicTo(r.left, mid, tl, mid, tl, bb);
        path.close();

        bubblePaint.setColor(th.bubble);
        bubblePaint.setShadowLayer(4 * dp, 0, 1 * dp, th.dark ? 0x80000000 : 0x55000000);
        c.drawPath(path, bubblePaint);

        boolean up = layout == L_LETTERS && shift != SHIFT_OFF;
        String s = up ? k.upper : k.label;
        boolean lowerLetter = !up && layout == L_LETTERS;
        bigText.setColor(th.text);
        float cy = (bt + bb) / 2f + kh * 0.05f;
        c.drawText(s, (tl + tr) / 2f, cy + (lowerLetter ? bigLowerOff : bigUpperOff), bigText);
    }

    private void drawAlts(Canvas c) {
        bubblePaint.setColor(th.bubble);
        bubblePaint.setShadowLayer(6 * dp, 0, 2 * dp, th.dark ? 0x90000000 : 0x60000000);
        c.drawRoundRect(altBox, radius * 1.3f, radius * 1.3f, bubblePaint);
        float pad = 4 * dp;
        float cy = altBox.centerY();
        for (int i = 0; i < altList.length; i++) {
            float l = altBox.left + pad + i * altCell;
            if (i == altSel) {
                fill.setColor(th.altSel);
                tmp.set(l + 1 * dp, altBox.top + pad, l + altCell - 1 * dp, altBox.bottom - pad);
                c.drawRoundRect(tmp, radius, radius, fill);
            }
            text.setColor(i == altSel ? 0xFFFFFFFF : th.text);
            text.setAlpha(255);
            String s = altList[i];
            boolean lower = s.equals(s.toLowerCase(Locale.ROOT)) && Character.isLetter(s.charAt(0));
            c.drawText(s, l + altCell / 2f, cy + (lower ? lowerOff : upperOff), text);
        }
    }

    // ------------------------------------------------------------------ icons

    private void drawShift(Canvas c, float cx, float cy, float s, int color, int alpha) {
        float t = cy - s / 2f - (shift == SHIFT_LOCK ? s * 0.06f : 0);
        float stem = s * 0.2f;
        float bottom = t + (shift == SHIFT_LOCK ? s * 0.8f : s * 0.92f);
        icon.reset();
        icon.moveTo(cx, t);
        icon.lineTo(cx + s * 0.5f, t + s * 0.5f);
        icon.lineTo(cx + stem, t + s * 0.5f);
        icon.lineTo(cx + stem, bottom);
        icon.lineTo(cx - stem, bottom);
        icon.lineTo(cx - stem, t + s * 0.5f);
        icon.lineTo(cx - s * 0.5f, t + s * 0.5f);
        icon.close();
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        c.drawPath(icon, stroke);
        if (shift != SHIFT_OFF) {
            fill.setColor(color);
            fill.setAlpha(alpha);
            c.drawPath(icon, fill);
        }
        if (shift == SHIFT_LOCK) {
            fill.setColor(color);
            fill.setAlpha(alpha);
            float y = bottom + s * 0.12f;
            c.drawRect(cx - stem - dp * 0.8f, y, cx + stem + dp * 0.8f, y + s * 0.12f, fill);
        }
        fill.setAlpha(255);
    }

    private void drawReturnIcon(Canvas c, float l, float cy, float s, int color, int alpha) {
        float r = l + s, y = cy + s * 0.32f, a = s * 0.3f;
        icon.reset();
        icon.moveTo(r, cy - s * 0.62f);
        icon.lineTo(r, y);
        icon.lineTo(l, y);
        icon.moveTo(l + a, y - a);
        icon.lineTo(l, y);
        icon.lineTo(l + a, y + a);
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        float w = stroke.getStrokeWidth();
        stroke.setStrokeWidth(1.4f * dp);
        c.drawPath(icon, stroke);
        stroke.setStrokeWidth(w);
    }

    private void drawDelete(Canvas c, float cx, float cy, float s, int color, int alpha) {
        float w = s * 1.4f, hh = s;
        float l = cx - w / 2f, r = cx + w / 2f, t = cy - hh / 2f, b = cy + hh / 2f;
        float tip = w * 0.3f, rr = s * 0.16f;
        icon.reset();
        icon.moveTo(l, cy);
        icon.lineTo(l + tip, t);
        icon.lineTo(r - rr, t);
        icon.quadTo(r, t, r, t + rr);
        icon.lineTo(r, b - rr);
        icon.quadTo(r, b, r - rr, b);
        icon.lineTo(l + tip, b);
        icon.close();
        float xc = (l + tip + r) / 2f + s * 0.02f, xs = hh * 0.2f;
        icon.moveTo(xc - xs, cy - xs);
        icon.lineTo(xc + xs, cy + xs);
        icon.moveTo(xc + xs, cy - xs);
        icon.lineTo(xc - xs, cy + xs);
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        c.drawPath(icon, stroke);
    }

    private void drawSmiley(Canvas c, float cx, float cy, float s, int color, int alpha) {
        float r = s / 2f;
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        c.drawCircle(cx, cy, r, stroke);
        fill.setColor(color);
        fill.setAlpha(alpha);
        c.drawCircle(cx - r * 0.36f, cy - r * 0.22f, r * 0.12f, fill);
        c.drawCircle(cx + r * 0.36f, cy - r * 0.22f, r * 0.12f, fill);
        fill.setAlpha(255);
        tmp.set(cx - r * 0.52f, cy - r * 0.42f, cx + r * 0.52f, cy + r * 0.55f);
        c.drawArc(tmp, 25, 130, false, stroke);
    }
}
