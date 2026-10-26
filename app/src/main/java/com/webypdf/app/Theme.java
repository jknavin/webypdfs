package com.webypdf.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;
import android.view.Window;

/** Colour palette (light / dark) and small unit helpers. */
public final class Theme {
    public static final int PURPLE = 0xFF7C3AED;
    public static final int PINK = 0xFFEC4899;
    public static final int ORANGE = 0xFFF59E0B;
    public static final int CYAN = 0xFF06B6D4;
    public static final int GREEN = 0xFF10B981;
    public static final int RED = 0xFFEF4444;

    public static boolean dark;
    public static int bg, surface, surfaceAlt, text, muted, stroke, nav, field;

    private Theme() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("webypdf", Context.MODE_PRIVATE);
    }

    /** 0 = follow system, 1 = light, 2 = dark. */
    public static int mode(Context c) {
        return prefs(c).getInt("theme", 0);
    }

    public static void setMode(Context c, int m) {
        prefs(c).edit().putInt("theme", m).apply();
    }

    /** Must be called in every Activity.onCreate before views are built. */
    public static void apply(Context c) {
        int m = mode(c);
        boolean sysDark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dark = m == 2 || (m == 0 && sysDark);
        if (dark) {
            bg = 0xFF0E0A22;
            surface = 0xFF1A1438;
            surfaceAlt = 0xFF261E50;
            text = 0xFFF5F2FF;
            muted = 0xFFA9A2CC;
            stroke = 0x2BFFFFFF;
            nav = 0xFF150F30;
            field = 0xFF241C4A;
        } else {
            bg = 0xFFF5F3FF;
            surface = 0xFFFFFFFF;
            surfaceAlt = 0xFFEFEBFF;
            text = 0xFF1B1340;
            muted = 0xFF6B6491;
            stroke = 0x1F1B1340;
            nav = 0xFFFFFFFF;
            field = 0xFFF1EEFF;
        }
    }

    public static int dp(float v) {
        return Math.round(v * Resources.getSystem().getDisplayMetrics().density);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Colours the status / navigation bars. */
    public static void window(Activity a, int statusColor, int navColor, boolean darkNavIcons) {
        Window w = a.getWindow();
        w.setStatusBarColor(statusColor);
        w.setNavigationBarColor(navColor);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; // gradient headers always use light (white) status icons
        if (Build.VERSION.SDK_INT >= 26) {
            if (darkNavIcons) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
        decor.setBackgroundColor(bg);
    }
}
