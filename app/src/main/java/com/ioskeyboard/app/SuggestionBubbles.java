package com.ioskeyboard.app;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.PopupWindow;

/**
 * iOS 26 style floating word suggestions: three tilted glass pills that hover just above the
 * text cursor (outside the keyboard), shown in a small popup window owned by the IME.
 */
final class SuggestionBubbles {
    private static final float[] TILT = {-7f, -2f, 7f};

    private final IosKeyboardService ime;
    private final BubbleView view;
    private final PopupWindow pop;
    private final float dp;
    private KbTheme th;

    private final String[] labels = new String[3];
    private final RectF[] pills = {new RectF(), new RectF(), new RectF()};
    private int count;
    private int pressed = -1;

    private int popW, popH;
    // difference between the coordinates we pass and where the window really lands
    private int corrX, corrY;
    private int wantX, wantY;
    private boolean measuredCorrection;

    SuggestionBubbles(IosKeyboardService ime) {
        this.ime = ime;
        dp = ime.getResources().getDisplayMetrics().density;
        view = new BubbleView();
        pop = new PopupWindow(view);
        pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pop.setClippingEnabled(false);
        pop.setTouchable(true);
        pop.setFocusable(false);
        pop.setOutsideTouchable(false);
        pop.setAnimationStyle(0);
        pop.setSplitTouchEnabled(false);
    }

    void setTheme(KbTheme t) {
        th = t;
        view.invalidate();
    }

    void hide() {
        pressed = -1;
        if (pop.isShowing()) {
            try {
                pop.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * @param anchorX  screen x of the cursor, or NaN when unknown
     * @param anchorY  screen y of the cursor top, or NaN when unknown
     */
    void show(View parent, String[] src, int n, float anchorX, float anchorY) {
        count = 0;
        for (int i = 0; i < 3; i++) {
            labels[i] = i < n ? src[i] : null;
            if (labels[i] != null) count++;
        }
        if (count == 0 || parent == null || parent.getWindowToken() == null || !parent.isAttachedToWindow()) {
            hide();
            return;
        }
        measure();

        int[] scr = new int[2];
        parent.getLocationOnScreen(scr);
        int screenW = parent.getRootView().getWidth();
        if (screenW <= 0) screenW = parent.getWidth();
        int kbTop = scr[1];

        float cx = Float.isNaN(anchorX) ? screenW / 2f : anchorX;
        int x = Math.round(cx - popW / 2f);
        x = Math.max((int) (4 * dp), Math.min(screenW - popW - (int) (4 * dp), x));
        int bottom;
        if (Float.isNaN(anchorY) || anchorY > kbTop) bottom = kbTop - (int) (4 * dp);
        else bottom = Math.min(kbTop - (int) (2 * dp), Math.round(anchorY + 10 * dp));
        int y = Math.max(0, bottom - popH);

        // PopupWindow coordinates of an IME sub-window are relative to the IME window
        int[] inWin = new int[2];
        parent.getLocationInWindow(inWin);
        int winX = scr[0] - inWin[0], winY = scr[1] - inWin[1];
        wantX = x;
        wantY = y;
        int px = x - winX + corrX, py = y - winY + corrY;

        boolean wasShowing = pop.isShowing();
        if (wasShowing) {
            pop.update(px, py, popW, popH);
            view.invalidate();
        } else {
            pop.setWidth(popW);
            pop.setHeight(popH);
            try {
                pop.showAtLocation(parent, Gravity.NO_GRAVITY, px, py);
            } catch (Throwable t) {
                return;
            }
            view.setPivotX(popW / 2f);
            view.setPivotY(popH);
            view.setScaleX(0.7f);
            view.setScaleY(0.7f);
            view.setAlpha(0f);
            view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.6f)).start();
        }
        if (!measuredCorrection) {
            view.post(() -> {
                if (!pop.isShowing()) return;
                int[] real = new int[2];
                view.getLocationOnScreen(real);
                int dx = wantX - real[0], dy = wantY - real[1];
                measuredCorrection = true;
                if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
                    corrX += dx;
                    corrY += dy;
                    int[] wl = new int[2];
                    parent.getLocationInWindow(wl);
                    int[] sl = new int[2];
                    parent.getLocationOnScreen(sl);
                    pop.update(wantX - (sl[0] - wl[0]) + corrX, wantY - (sl[1] - wl[1]) + corrY, popW, popH);
                }
            });
        }
    }

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

    {
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        rim.setStyle(Paint.Style.STROKE);
    }

    private void measure() {
        text.setTextSize(17 * dp);
        rim.setStrokeWidth(0.8f * dp);
        float h = 38 * dp, padX = 18 * dp, minW = 58 * dp, overlap = 10 * dp;
        float margin = 10 * dp; // room for tilt + shadow
        float x = margin;
        float lift = 13 * dp; // middle pill sits higher
        for (int i = 0; i < 3; i++) {
            if (labels[i] == null) {
                pills[i].setEmpty();
                continue;
            }
            float w = Math.max(minW, text.measureText(labels[i]) + padX * 2);
            w = Math.min(w, 160 * dp);
            float top = margin + (i == 1 ? 0 : lift);
            pills[i].set(x, top, x + w, top + h);
            x += w - overlap;
        }
        popW = (int) Math.ceil(x + overlap + margin);
        popH = (int) Math.ceil(margin * 2 + lift + h);
    }

    private String fit(String s, float max) {
        if (text.measureText(s) <= max) return s;
        int n = text.breakText(s, true, max - text.measureText("…"), null);
        return s.substring(0, Math.max(1, n)) + "…";
    }

    private final class BubbleView extends View {
        BubbleView() {
            super(ime);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (th == null) return;
            // middle first, sides overlap it like the iOS stack
            drawPill(c, 1);
            drawPill(c, 0);
            drawPill(c, 2);
        }

        private void drawPill(Canvas c, int i) {
            if (labels[i] == null) return;
            RectF r = pills[i];
            float rad = r.height() / 2f;
            c.save();
            c.rotate(TILT[i], r.centerX(), r.centerY());
            fill.setColor(i == pressed ? th.pillPressed : th.pill);
            fill.setShadowLayer(6 * dp, 0, 2 * dp, th.dark ? 0x66000000 : 0x33000000);
            c.drawRoundRect(r, rad, rad, fill);
            fill.clearShadowLayer();
            rim.setColor(th.pillRim);
            c.drawRoundRect(r, rad, rad, rim);
            text.setColor(th.pillText);
            Paint.FontMetrics fm = text.getFontMetrics();
            float ty = r.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(fit(labels[i], r.width() - 20 * dp), r.centerX(), ty, text);
            c.restore();
        }

        private int hit(float x, float y) {
            int[] order = {2, 0, 1}; // top-most first
            for (int i : order) {
                if (labels[i] == null) continue;
                RectF r = pills[i];
                if (x >= r.left - 2 * dp && x <= r.right + 2 * dp && y >= r.top - 6 * dp && y <= r.bottom + 6 * dp)
                    return i;
            }
            return -1;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = hit(e.getX(), e.getY());
                    if (pressed >= 0) {
                        ime.feedback(Feedback.KEY);
                        animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
                    }
                    invalidate();
                    return pressed >= 0;
                case MotionEvent.ACTION_MOVE: {
                    int h = hit(e.getX(), e.getY());
                    if (h != pressed && pressed >= 0 && h < 0) {
                        pressed = -1;
                        animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    int p = pressed;
                    pressed = -1;
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    invalidate();
                    if (p >= 0 && hit(e.getX(), e.getY()) == p) ime.onSuggestion(p);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    pressed = -1;
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    invalidate();
                    return true;
            }
            return true;
        }
    }
}
