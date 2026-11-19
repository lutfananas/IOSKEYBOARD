package com.ioskeyboard.app;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full emoji picker shown in place of the keys. */
@SuppressLint("ViewConstructor")
final class EmojiPanel extends LinearLayout {
    private final IosKeyboardService ime;
    private final float dp;
    private final TextView title;
    private final GridView grid;
    private final LinearLayout bar;
    private final TextView[] tabs = new TextView[EmojiData.TAB_ICONS.length];
    private final TextView abc, del;
    private String[] items = new String[0];
    private int cat = 1;
    private KbTheme th;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int delCount;

    private final Runnable delRepeat = new Runnable() {
        @Override
        public void run() {
            delCount++;
            ime.onDelete(false);
            ime.feedback(Feedback.DELETE);
            h.postDelayed(this, delCount > 20 ? 45 : 70);
        }
    };

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return items.length; }
        @Override public Object getItem(int i) { return items[i]; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            TextView t = (TextView) v;
            if (t == null) {
                t = new TextView(getContext());
                t.setGravity(Gravity.CENTER);
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 29);
                t.setIncludeFontPadding(false);
                t.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (46 * dp)));
            }
            t.setText(items[i]);
            return t;
        }
    };

    EmojiPanel(IosKeyboardService ime) {
        super(ime);
        this.ime = ime;
        dp = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        title = new TextView(ime);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        title.setLetterSpacing(0.04f);
        title.setPadding((int) (12 * dp), (int) (8 * dp), 0, (int) (2 * dp));
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        grid = new GridView(ime);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth((int) (44 * dp));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(OVER_SCROLL_NEVER);
        grid.setPadding((int) (4 * dp), 0, (int) (4 * dp), 0);
        grid.setClipToPadding(false);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < items.length) {
                ime.feedback(Feedback.KEY);
                ime.onEmoji(items[pos]);
                if (cat == 0) return; // don't reshuffle recents while browsing them
            }
        });
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        bar = new LinearLayout(ime);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (4 * dp), 0, (int) (4 * dp), (int) (2 * dp));
        abc = barText("ABC", 15);
        abc.setOnClickListener(v -> {
            ime.feedback(Feedback.KEY);
            ime.showEmoji(false);
        });
        bar.addView(abc, new LayoutParams((int) (48 * dp), LayoutParams.MATCH_PARENT));
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            TextView t = barText(EmojiData.TAB_ICONS[i], 18);
            t.setOnClickListener(v -> {
                ime.feedback(Feedback.KEY);
                select(idx);
            });
            tabs[i] = t;
            bar.addView(t, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        }
        del = barText("⌫", 20);
        del.setOnTouchListener(this::onDelTouch);
        bar.addView(del, new LayoutParams((int) (48 * dp), LayoutParams.MATCH_PARENT));
        addView(bar, new LayoutParams(LayoutParams.MATCH_PARENT, (int) (44 * dp)));
    }

    private TextView barText(String s, int sp) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        return t;
    }

    @SuppressLint("ClickableViewAccessibility")
    private boolean onDelTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setAlpha(0.5f);
                ime.feedback(Feedback.DELETE);
                ime.onDelete(false);
                delCount = 0;
                h.postDelayed(delRepeat, 420);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.setAlpha(1f);
                h.removeCallbacks(delRepeat);
                return true;
        }
        return true;
    }

    void applyTheme(KbTheme t) {
        th = t;
        setBackgroundColor(t.bg);
        title.setTextColor(t.dark ? 0xFF98989F : 0xFF6C6C70);
        abc.setTextColor(t.text);
        del.setTextColor(t.text);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(t.stripPress);
        pressed.setCornerRadius(10 * dp);
        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sel.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        grid.setSelector(sel);
        grid.setDrawSelectorOnTop(true);
        updateTabs();
    }

    void open() {
        select(ime.recentEmoji().isEmpty() ? 1 : 0);
    }

    private void select(int c) {
        cat = c;
        if (c == 0) items = ime.recentEmoji().toArray(new String[0]);
        else items = EmojiData.CATS[c];
        title.setText(EmojiData.TAB_NAMES[c]);
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        updateTabs();
    }

    private void updateTabs() {
        if (th == null) return;
        for (int i = 0; i < tabs.length; i++) {
            TextView t = tabs[i];
            if (i == cat) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(th.dark ? 0x33FFFFFF : 0x22000000);
                g.setCornerRadius(16 * dp);
                t.setBackground(g);
                t.setAlpha(1f);
            } else {
                t.setBackground(null);
                t.setAlpha(0.55f);
            }
        }
    }
}
