package com.webypdf.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two steps: (1) draw or type a signature, (2) drag it onto a page preview.
 * Returns: sig (png path), page (0-based), x / y (centre, 0..1 from top-left), w (width fraction), apply.
 */
public class SignActivity extends Activity {

    private static final int[] INK = {0xFF111827, 0xFF1D4ED8, 0xFFDC2626, 0xFF059669};

    private int c1;
    private int c2;
    private File pdfFile;
    private int pageCount;

    private PdfPages pdf;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private LinearLayout step1;
    private LinearLayout step2;
    private TextView stepAction;

    // step 1
    private boolean typeMode;
    private int inkColor = INK[0];
    private InkView ink;
    private EditText typed;
    private int typeFace;
    private LinearLayout drawBox;
    private LinearLayout typeBox;
    private TextView tabDraw;
    private TextView tabType;
    private LinearLayout inkRow;
    private LinearLayout fontRow;

    // step 2
    private int onStep = 1;
    private Bitmap sigBitmap;
    private String sigPath;
    private int page;
    private float sx = 0.7f;
    private float sy = 0.85f;
    private float sw = 0.3f;
    private String apply = "current";
    private FrameLayout stage;
    private ImageView pageImg;
    private ImageView sigImg;
    private TextView pageLabel;
    private int stageW;
    private int stageH;
    private final List<TextView> applyChips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.apply(this);
        c1 = getIntent().getIntExtra("c1", Theme.PINK);
        c2 = getIntent().getIntExtra("c2", Theme.ORANGE);
        pdfFile = new File(getIntent().getStringExtra("path"));
        pageCount = Math.max(1, getIntent().getIntExtra("pages", 1));
        try {
            pdf = new PdfPages(pdfFile);
            pageCount = pdf.count;
        } catch (Throwable e) {
            Toast.makeText(this, "Could not open this PDF", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Theme.window(this, c1, Theme.nav, !Theme.dark);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Theme.bg);
        page.addView(Ui.header(this, "Sign PDF", "Draw or type, then drag it into place", c1, c2, null));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(16));
        step1 = buildStep1();
        step2 = buildStep2();
        step2.setVisibility(View.GONE);
        holder.addView(step1, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        holder.addView(step2, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        sv.addView(holder, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        page.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Theme.nav);
        bar.addView(Ui.divider(this));
        stepAction = Ui.primaryButton(this, "Next: place signature");
        stepAction.setOnClickListener(v -> {
            if (onStep == 1) goStep2();
            else finishWithResult();
        });
        bar.addView(stepAction, Ui.lp(Ui.MATCH, Ui.WRAP, 16, 12, 16, 12));
        page.addView(bar);
        setContentView(page);
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        if (pdf != null) pdf.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (onStep == 2) {
            showStep(1);
            return;
        }
        super.onBackPressed();
    }

    private void showStep(int s) {
        onStep = s;
        step1.setVisibility(s == 1 ? View.VISIBLE : View.GONE);
        step2.setVisibility(s == 2 ? View.VISIBLE : View.GONE);
        stepAction.setText(s == 1 ? "Next: place signature" : "Use this signature");
    }

    /* ============================ step 1 ============================ */
    private LinearLayout buildStep1() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabDraw = Ui.chip(this, "Draw", true);
        tabType = Ui.chip(this, "Type", false);
        tabDraw.setOnClickListener(v -> setTypeMode(false));
        tabType.setOnClickListener(v -> setTypeMode(true));
        tabs.addView(tabDraw, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
        tabs.addView(tabType);
        box.addView(tabs, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

        // draw
        drawBox = new LinearLayout(this);
        drawBox.setOrientation(LinearLayout.VERTICAL);
        ink = new InkView(this);
        ink.setBackground(Ui.stroked(0xFFFFFFFF, Theme.stroke, 18, 1));
        drawBox.addView(ink, new LinearLayout.LayoutParams(Ui.MATCH, Ui.dp(220)));
        TextView hint = Ui.text(this, "Sign with your finger inside the box", 12.5f, Theme.muted, false);
        drawBox.addView(hint, Ui.lp(Ui.WRAP, Ui.WRAP, 4, 8, 0, 0));
        TextView clear = Ui.softButton(this, "Clear");
        clear.setOnClickListener(v -> ink.clear());
        drawBox.addView(clear, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 10, 0, 0));
        box.addView(drawBox, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // type
        typeBox = new LinearLayout(this);
        typeBox.setOrientation(LinearLayout.VERTICAL);
        typed = Ui.input(this, "Type your name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, 1);
        typed.setTextSize(22);
        typed.setTypeface(faceFor(0));
        typed.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        });
        typeBox.addView(typed, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] fn = {"Cursive", "Serif", "Classic"};
        for (int i = 0; i < fn.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, fn[i], i == 0);
            c.setTypeface(faceFor(i));
            c.setOnClickListener(v -> {
                typeFace = idx;
                typed.setTypeface(faceFor(idx));
                for (int k = 0; k < fontRow.getChildCount(); k++) {
                    fontRow.getChildAt(k).setBackground(chipBg(k == idx));
                    ((TextView) fontRow.getChildAt(k)).setTextColor(k == idx ? Color.WHITE : Theme.text);
                }
            });
            fontRow.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
        }
        typeBox.addView(fontRow, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 14, 0, 0));
        typeBox.setVisibility(View.GONE);
        box.addView(typeBox, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        // ink colour
        box.addView(Ui.text(this, "Ink colour", 12.5f, Theme.muted, true), Ui.lp(Ui.WRAP, Ui.WRAP, 2, 20, 0, 8));
        inkRow = new LinearLayout(this);
        inkRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < INK.length; i++) {
            final int col = INK[i];
            FrameLayout sw = new FrameLayout(this);
            sw.setOnClickListener(v -> {
                inkColor = col;
                ink.setColor(col);
                paintInk();
            });
            inkRow.addView(sw, Ui.lp(Ui.dp(38), Ui.dp(38), 0, 0, 10, 0));
        }
        box.addView(inkRow);
        paintInk();
        return box;
    }

    private android.graphics.drawable.Drawable chipBg(boolean on) {
        return on ? Ui.gradient(c1, c2, 20) : Ui.stroked(Theme.field, Theme.stroke, 20, 1);
    }

    private static Typeface faceFor(int i) {
        switch (i) {
            case 1:
                return Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC);
            case 2:
                return Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC);
            default:
                return Typeface.create("cursive", Typeface.ITALIC);
        }
    }

    private void setTypeMode(boolean t) {
        typeMode = t;
        drawBox.setVisibility(t ? View.GONE : View.VISIBLE);
        typeBox.setVisibility(t ? View.VISIBLE : View.GONE);
        tabDraw.setBackground(chipBg(!t));
        tabDraw.setTextColor(!t ? Color.WHITE : Theme.text);
        tabType.setBackground(chipBg(t));
        tabType.setTextColor(t ? Color.WHITE : Theme.text);
    }

    private void paintInk() {
        for (int i = 0; i < inkRow.getChildCount(); i++) {
            FrameLayout sw = (FrameLayout) inkRow.getChildAt(i);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(INK[i]);
            boolean on = INK[i] == inkColor;
            g.setStroke(Ui.dp(on ? 3 : 1), on ? Theme.text : Theme.stroke);
            sw.setBackground(g);
            sw.removeAllViews();
            if (on) {
                ImageView ck = new ImageView(this);
                ck.setImageResource(R.drawable.ic_check);
                ck.setColorFilter(Color.WHITE);
                sw.addView(ck, new FrameLayout.LayoutParams(Ui.dp(18), Ui.dp(18), Gravity.CENTER));
            }
        }
    }

    /** Builds the signature bitmap from whichever tab is active, trimmed to its ink. */
    private Bitmap makeSignature() {
        Bitmap raw;
        if (typeMode) {
            String s = typed.getText().toString().trim();
            if (s.isEmpty()) return null;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(faceFor(typeFace));
            p.setTextSize(150);
            p.setColor(inkColor);
            int w = (int) Math.ceil(p.measureText(s)) + 80;
            int h = 260;
            w = Math.min(w, 4000);
            raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(raw);
            c.drawText(s, 40, 170, p);
        } else {
            if (!ink.hasInk()) return null;
            raw = ink.render();
        }
        return trim(raw);
    }

    private static Bitmap trim(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) return src;
        int pad = 10;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(w - 1, maxX + pad);
        maxY = Math.min(h - 1, maxY + pad);
        Bitmap out = Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1);
        return out;
    }

    private void goStep2() {
        Bitmap bm = makeSignature();
        if (bm == null) {
            Toast.makeText(this, typeMode ? "Type your name first" : "Draw your signature first", Toast.LENGTH_SHORT).show();
            return;
        }
        sigBitmap = bm;
        try {
            File f = new File(Storage.tmpDir(this), "signature_" + System.currentTimeMillis() + ".png");
            FileOutputStream fo = new FileOutputStream(f);
            try {
                bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
            } finally {
                fo.close();
            }
            sigPath = f.getAbsolutePath();
        } catch (Exception e) {
            Toast.makeText(this, "Could not save the signature", Toast.LENGTH_LONG).show();
            return;
        }
        sigImg.setImageBitmap(sigBitmap);
        showStep(2);
        loadPage();
    }

    /* ============================ step 2 ============================ */
    private LinearLayout buildStep2() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // page switcher
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout prev = Ui.roundIconButton(this, R.drawable.ic_left, Theme.text, Theme.surface, 38);
        prev.setOnClickListener(v -> changePage(-1));
        FrameLayout next = Ui.roundIconButton(this, R.drawable.ic_right, Theme.text, Theme.surface, 38);
        next.setOnClickListener(v -> changePage(1));
        pageLabel = Ui.text(this, "", 15, Theme.text, true);
        pageLabel.setGravity(Gravity.CENTER);
        nav.addView(prev, new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));
        nav.addView(pageLabel, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        nav.addView(next, new LinearLayout.LayoutParams(Ui.dp(38), Ui.dp(38)));
        box.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 12));

        // stage
        DisplayMetrics dm = getResources().getDisplayMetrics();
        stageW = dm.widthPixels - Ui.dp(32);
        stageH = (int) (stageW * 1.4f);
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.WHITE);
        pageImg = new ImageView(this);
        pageImg.setScaleType(ImageView.ScaleType.FIT_XY);
        stage.addView(pageImg, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        sigImg = new ImageView(this);
        sigImg.setScaleType(ImageView.ScaleType.FIT_XY);
        sigImg.setBackground(Ui.dashed(0x1A7C3AED, Theme.PURPLE, 4));
        sigImg.setOnTouchListener(new DragListener());
        stage.addView(sigImg, new FrameLayout.LayoutParams(Ui.dp(100), Ui.dp(40)));
        box.addView(stage, new LinearLayout.LayoutParams(stageW, stageH));

        TextView tip = Ui.text(this, "Drag the signature to where it should go", 12.5f, Theme.muted, false);
        tip.setGravity(Gravity.CENTER);
        box.addView(tip, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 8, 0, 0));

        // size
        LinearLayout size = new LinearLayout(this);
        size.setOrientation(LinearLayout.HORIZONTAL);
        size.setGravity(Gravity.CENTER_VERTICAL);
        size.addView(Ui.text(this, "Size", 13, Theme.muted, true));
        SeekBar sb = new SeekBar(this);
        sb.setMax(60);
        sb.setProgress((int) (sw * 100) - 10);
        sb.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.PINK));
        sb.setThumbTintList(android.content.res.ColorStateList.valueOf(Theme.PINK));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                sw = (p + 10) / 100f;
                layoutSig();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        sl.setMargins(Ui.dp(12), 0, 0, 0);
        size.addView(sb, sl);
        box.addView(size, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));

        // apply to
        box.addView(Ui.text(this, "Add to", 12.5f, Theme.muted, true), Ui.lp(Ui.WRAP, Ui.WRAP, 2, 14, 0, 8));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final String[][] opts = {{"current", "This page"}, {"first", "First"}, {"last", "Last"}, {"all", "All pages"}};
        for (final String[] o : opts) {
            final TextView c = Ui.chip(this, o[1], o[0].equals(apply));
            c.setOnClickListener(v -> {
                apply = o[0];
                paintApply(opts);
            });
            applyChips.add(c);
            row.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        box.addView(hs);
        return box;
    }

    private void paintApply(String[][] opts) {
        for (int i = 0; i < applyChips.size(); i++) {
            boolean on = opts[i][0].equals(apply);
            applyChips.get(i).setBackground(chipBg(on));
            applyChips.get(i).setTextColor(on ? Color.WHITE : Theme.text);
        }
    }

    private void changePage(int d) {
        int np = Math.max(0, Math.min(pageCount - 1, page + d));
        if (np == page) return;
        page = np;
        loadPage();
    }

    private void loadPage() {
        pageLabel.setText("Page " + (page + 1) + " of " + pageCount);
        final int p = page;
        try {
            exec.execute(() -> {
                try {
                    float[] sz = pdf.size(p);
                    final Bitmap b = pdf.renderWidth(p, 900);
                    final float aspect = sz[1] / sz[0];
                    runOnUiThread(() -> {
                        if (p != page) return;
                        stageH = Math.round(stageW * aspect);
                        stage.getLayoutParams().height = stageH;
                        stage.requestLayout();
                        pageImg.setImageBitmap(b);
                        layoutSig();
                    });
                } catch (Throwable ignored) {
                    // preview optional
                }
            });
        } catch (Exception ignored) {
            // stopped
        }
        layoutSig();
    }

    private void layoutSig() {
        if (sigBitmap == null) return;
        int w = Math.round(stageW * sw);
        int h = Math.round(w * sigBitmap.getHeight() / (float) sigBitmap.getWidth());
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sigImg.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = Math.round(sx * stageW - w / 2f);
        lp.topMargin = Math.round(sy * stageH - h / 2f);
        sigImg.setLayoutParams(lp);
    }

    private final class DragListener implements View.OnTouchListener {
        private float downX;
        private float downY;
        private float startSx;
        private float startSy;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startSx = sx;
                    startSy = sy;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    sx = clamp(startSx + (e.getRawX() - downX) / stageW);
                    sy = clamp(startSy + (e.getRawY() - downY) / Math.max(1, stageH));
                    layoutSig();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return false;
            }
        }
    }

    private static float clamp(float v) {
        return Math.max(0.02f, Math.min(0.98f, v));
    }

    private void finishWithResult() {
        if (sigPath == null) return;
        Intent r = new Intent();
        r.putExtra("sig", sigPath);
        r.putExtra("page", page);
        r.putExtra("x", sx);
        r.putExtra("y", sy);
        r.putExtra("w", sw);
        r.putExtra("apply", apply);
        setResult(RESULT_OK, r);
        finish();
    }

    /* ============================ drawing view ============================ */
    private static final class InkView extends View {
        private final List<Path> paths = new ArrayList<>();
        private final List<Integer> colors = new ArrayList<>();
        private Path cur;
        private int color = INK[0];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        InkView(Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Ui.dp(3.2f));
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        boolean hasInk() {
            return !paths.isEmpty();
        }

        void clear() {
            paths.clear();
            colors.clear();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            for (int i = 0; i < paths.size(); i++) {
                paint.setColor(color);
                c.drawPath(paths.get(i), paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float x = e.getX();
            float y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    cur = new Path();
                    cur.moveTo(x, y);
                    cur.lineTo(x + 0.1f, y + 0.1f); // dots count
                    paths.add(cur);
                    colors.add(color);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (cur != null) {
                        for (int i = 0; i < e.getHistorySize(); i++) {
                            cur.lineTo(e.getHistoricalX(i), e.getHistoricalY(i));
                        }
                        cur.lineTo(x, y);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cur = null;
                    return true;
                default:
                    return false;
            }
        }

        /** Transparent bitmap of the strokes, drawn larger for a crisp result. */
        Bitmap render() {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            float k = 3f;
            Bitmap b = Bitmap.createBitmap((int) (w * k), (int) (h * k), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(k, k);
            Paint p = new Paint(paint);
            for (Path path : paths) {
                p.setColor(color);
                c.drawPath(path, p);
            }
            return b;
        }
    }
}
