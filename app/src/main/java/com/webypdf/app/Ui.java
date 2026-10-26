package com.webypdf.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny programmatic UI kit shared by every screen. */
public final class Ui {
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Ui() {
    }

    public static int dp(float v) {
        return Theme.dp(v);
    }

    /* ------------------------------ drawables ------------------------------ */
    public static GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public static GradientDrawable stroked(int fill, int strokeColor, float radiusDp, float widthDp) {
        GradientDrawable g = shape(fill, radiusDp);
        g.setStroke(Math.max(1, dp(widthDp)), strokeColor);
        return g;
    }

    public static GradientDrawable dashed(int fill, int strokeColor, float radiusDp) {
        GradientDrawable g = shape(fill, radiusDp);
        g.setStroke(dp(1.5f), strokeColor, dp(6), dp(5));
        return g;
    }

    public static GradientDrawable gradient(int c1, int c2, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public static GradientDrawable brand(float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Theme.PURPLE, Theme.PINK, Theme.ORANGE});
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Gradient with only the bottom corners rounded (used by screen headers). */
    public static GradientDrawable headerBg(int c1, int c2, float bottomRadiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        float r = dp(bottomRadiusDp);
        g.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
        return g;
    }

    public static Drawable ripple(Drawable content, float radiusDp) {
        int rc = Theme.dark ? 0x33FFFFFF : 0x22000000;
        return new RippleDrawable(ColorStateList.valueOf(rc), content, shape(Color.WHITE, radiusDp));
    }

    /* ------------------------------ layout params ------------------------------ */
    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    public static LinearLayout.LayoutParams weight(float weight, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, weight);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /* ------------------------------ views ------------------------------ */
    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView ellipsized(TextView t, int lines) {
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static ImageView icon(Context c, int res, float sizeDp, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** Rounded gradient square with a white icon – the signature look of the app. */
    public static FrameLayout iconTile(Context c, int icon, int c1, int c2, float sizeDp, float radiusDp) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(gradient(c1, c2, radiusDp));
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(Color.WHITE);
        int inner = dp(sizeDp * 0.56f);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER);
        f.addView(iv, ip);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(16));
        l.setBackground(stroked(Theme.surface, Theme.stroke, 20, 1));
        if (!Theme.dark) {
            l.setElevation(dp(2));
        }
        return l;
    }

    public static TextView primaryButton(Context c, String label) {
        TextView t = text(c, label, 16, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(15), dp(18), dp(15));
        t.setBackground(ripple(brand(16), 16));
        t.setClickable(true);
        return t;
    }

    public static TextView softButton(Context c, String label) {
        TextView t = text(c, label, 14, Theme.text, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setBackground(ripple(stroked(Theme.field, Theme.stroke, 14, 1), 14));
        t.setClickable(true);
        return t;
    }

    public static TextView chip(Context c, String label, boolean selected) {
        TextView t = text(c, label, 13.5f, selected ? Color.WHITE : Theme.text, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(9), dp(16), dp(9));
        t.setBackground(selected ? brand(20) : stroked(Theme.surface, Theme.stroke, 20, 1));
        t.setClickable(true);
        return t;
    }

    /** Circular translucent button (used in gradient headers). */
    public static FrameLayout roundIconButton(Context c, int icon, int tint, int bgColor, float sizeDp) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(bgColor);
        f.setBackground(g);
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(tint);
        int inner = dp(sizeDp * 0.5f);
        f.addView(iv, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        f.setClickable(true);
        return f;
    }

    public static View spacer(Context c, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    /** Rounded text field that matches the theme. */
    public static android.widget.EditText input(Context c, String hint, int inputType, int minLines) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setHintTextColor(Theme.muted);
        e.setTextColor(Theme.text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        e.setBackground(stroked(Theme.field, Theme.stroke, 14, 1));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setInputType(inputType);
        if (minLines > 1) {
            e.setMinLines(minLines);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    public static TextView sectionTitle(Context c, String s) {
        TextView t = text(c, s, 17, Theme.text, true);
        t.setLayoutParams(lp(WRAP, WRAP, 4, 0, 0, 0));
        return t;
    }

    /** Theme for system dialogs so they match light / dark mode. */
    public static int dialogTheme() {
        return Theme.dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.stroke);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 1));
        return v;
    }

    /**
     * Standard gradient screen header: back button, title, subtitle and an optional trailing view.
     */
    public static LinearLayout header(final Activity a, String title, String subtitle, int c1, int c2, View trailing) {
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(12), dp(12), dp(16), dp(20));
        h.setBackground(headerBg(c1, c2, 28));

        FrameLayout back = roundIconButton(a, R.drawable.ic_back, Color.WHITE, 0x33FFFFFF, 40);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.onBackPressed();
            }
        });
        h.addView(back);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ellipsized(text(a, title, 20, Color.WHITE, true), 1));
        if (subtitle != null && !subtitle.isEmpty()) {
            col.addView(ellipsized(text(a, subtitle, 13, 0xCCFFFFFF, false), 2), lp(WRAP, WRAP, 0, 3, 0, 0));
        }
        h.addView(col, new LinearLayout.LayoutParams(0, WRAP, 1f));
        ((LinearLayout.LayoutParams) col.getLayoutParams()).setMargins(dp(12), 0, dp(8), 0);
        if (trailing != null) {
            h.addView(trailing);
        }
        return h;
    }
}
