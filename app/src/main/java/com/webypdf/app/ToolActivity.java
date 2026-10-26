package com.webypdf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One screen that drives every tool: pick files, adjust options, run, then open / share / save the result.
 * What is shown is decided entirely by the {@link Tool} definition.
 */
public class ToolActivity extends Activity {

    private static final int REQ_PICK = 11;
    private static final int REQ_PAGES = 12;
    private static final int REQ_SIGN = 13;

    private Tool tool;

    private final List<Tool.InFile> files = new ArrayList<>();
    private final Map<String, String> values = new HashMap<>();
    private final List<OptView> optViews = new ArrayList<>();
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();

    // views
    private FrameLayout root;
    private LinearLayout col;
    private LinearLayout inputCard;
    private LinearLayout extrasBox;
    private LinearLayout optionsBox;
    private LinearLayout resultBox;
    private View bottomBar;
    private TextView cta;
    private FrameLayout overlay;
    private TextView overlayText;
    private ProgressBar overlayBar;
    private ScrollView scroll;

    // tool-specific state
    private int[] selection = new int[0];
    private int[] orgSrc;
    private int[] orgRot;
    private TextView selSummary;
    private TextView orgSummary;
    private TextView sigSummary;
    private String sigPath;
    private int sigPage;
    private float sigX = 0.7f;
    private float sigY = 0.85f;
    private float sigW = 0.3f;
    private String sigApply = "current";
    private EditText textInput;
    private final Map<String, EditText> metaInputs = new LinkedHashMap<>();
    private Switch metaClear;

    private boolean busy;
    private boolean showingResult;

    private static final class OptView {
        final Tool.Opt opt;
        final View view;

        OptView(Tool.Opt opt, View view) {
            this.opt = opt;
            this.view = view;
        }
    }

    /* ===================================================================== *
     *  Lifecycle
     * ===================================================================== */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.apply(this);
        tool = Tools.byId(getIntent().getStringExtra("tool"));
        if (tool == null) {
            finish();
            return;
        }
        Theme.window(this, tool.c1, Theme.nav, !Theme.dark);

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.bg);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(Ui.header(this, tool.name, tool.desc, tool.c1, tool.c2, headerIcon()));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(24));
        scroll.addView(col, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        page.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        page.addView(buildBottomBar(), new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        root.addView(page, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        root.addView(buildOverlay(), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        setContentView(root);

        buildBody();
        refreshState();
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            Toast.makeText(this, "Please wait, working…", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    private View headerIcon() {
        FrameLayout f = Ui.iconTile(this, tool.icon, 0x33FFFFFF, 0x33FFFFFF, 44, 14);
        return f;
    }

    /* ===================================================================== *
     *  Layout pieces
     * ===================================================================== */
    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Theme.nav);
        bar.addView(Ui.divider(this));
        cta = Ui.primaryButton(this, tool.cta);
        cta.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTool();
            }
        });
        LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP, 16, 12, 16, 12);
        bar.addView(cta, lp);
        bottomBar = bar;
        return bar;
    }

    private View buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB0050212);
        overlay.setClickable(true);
        overlay.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(Ui.dp(24), Ui.dp(24), Ui.dp(24), Ui.dp(24));
        card.setBackground(Ui.stroked(Theme.surface, Theme.stroke, 24, 1));

        ProgressBar spin = new ProgressBar(this);
        spin.setIndeterminateTintList(ColorStateList.valueOf(Theme.PINK));
        card.addView(spin, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));

        overlayText = Ui.text(this, "Working…", 15, Theme.text, true);
        overlayText.setGravity(Gravity.CENTER);
        card.addView(overlayText, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 16, 0, 0));

        overlayBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overlayBar.setMax(1000);
        overlayBar.setProgressTintList(ColorStateList.valueOf(Theme.PINK));
        overlayBar.setProgressBackgroundTintList(ColorStateList.valueOf(Theme.stroke));
        card.addView(overlayBar, Ui.lp(Ui.MATCH, Ui.dp(8), 0, 14, 0, 0));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(280), Ui.WRAP, Gravity.CENTER);
        overlay.addView(card, lp);
        return overlay;
    }

    private void showBusy(boolean on, String label) {
        busy = on;
        overlay.setVisibility(on ? View.VISIBLE : View.GONE);
        if (label != null) overlayText.setText(label);
        overlayBar.setProgress(0);
    }

    /** Builds the (initially empty) input, extras and options sections. */
    private void buildBody() {
        col.removeAllViews();

        if (tool.kind != Tool.Kind.NONE) {
            inputCard = Ui.card(this);
            col.addView(inputCard, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        }

        extrasBox = new LinearLayout(this);
        extrasBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(extrasBox, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        buildExtras();

        optionsBox = new LinearLayout(this);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(optionsBox, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        buildOptions();

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(resultBox, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        renderFiles();
    }

    /** Shows / hides sections depending on whether files are selected. */
    private void refreshState() {
        boolean has = !files.isEmpty();
        boolean show = (tool.kind == Tool.Kind.NONE || has) && !showingResult;
        extrasBox.setVisibility(show ? View.VISIBLE : View.GONE);
        optionsBox.setVisibility(show && !tool.opts.isEmpty() ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(showingResult ? View.GONE : View.VISIBLE);
        boolean ready = tool.kind == Tool.Kind.NONE || files.size() >= tool.minFiles;
        cta.setAlpha(ready ? 1f : 0.45f);
        if (inputCard != null) inputCard.setVisibility(showingResult ? View.GONE : View.VISIBLE);
    }

    /* ===================================================================== *
     *  Input files
     * ===================================================================== */
    private void renderFiles() {
        if (inputCard == null) return;
        inputCard.removeAllViews();
        final boolean images = tool.kind == Tool.Kind.IMAGES;

        if (files.isEmpty()) {
            inputCard.setGravity(Gravity.CENTER_HORIZONTAL);
            inputCard.setBackground(Ui.dashed(Theme.surface, Theme.withAlpha(tool.c1, 160), 22));
            inputCard.setPadding(Ui.dp(20), Ui.dp(30), Ui.dp(20), Ui.dp(30));
            inputCard.addView(Ui.iconTile(this, R.drawable.ic_upload, tool.c1, tool.c2, 64, 20));
            String what = images ? "images" : "PDF";
            String title = tool.multi ? "Select " + (images ? "images" : "PDF files") : "Select a PDF";
            inputCard.addView(Ui.text(this, title, 18, Theme.text, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 16, 0, 0));
            String sub = tool.multi
                    ? "Pick one or more " + what + " from your phone" + (tool.minFiles > 1 ? " (at least " + tool.minFiles + ")" : "")
                    : "Pick a PDF from your phone. It stays on your device.";
            TextView s = Ui.text(this, sub, 13.5f, Theme.muted, false);
            s.setGravity(Gravity.CENTER);
            inputCard.addView(s, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 6, 0, 18));
            TextView btn = Ui.primaryButton(this, "Choose " + (images ? "images" : tool.multi ? "files" : "file"));
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick();
                }
            });
            inputCard.addView(btn, new LinearLayout.LayoutParams(Ui.dp(210), Ui.WRAP));
            inputCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick();
                }
            });
            return;
        }

        inputCard.setOnClickListener(null);
        inputCard.setClickable(false);
        inputCard.setGravity(Gravity.START);
        inputCard.setBackground(Ui.stroked(Theme.surface, Theme.stroke, 20, 1));
        inputCard.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, tool.multi ? "Selected (" + files.size() + ")" : "Selected file", 16, Theme.text, true),
                new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        TextView add = Ui.softButton(this, tool.multi ? "+ Add more" : "Change");
        add.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });
        head.addView(add);
        inputCard.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));

        for (int i = 0; i < files.size(); i++) {
            inputCard.addView(fileRow(i), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        }
        if (tool.minFiles > 1 && files.size() < tool.minFiles) {
            TextView need = Ui.text(this, "Add at least " + tool.minFiles + " files to continue.", 13, Theme.ORANGE, true);
            inputCard.addView(need, Ui.lp(Ui.WRAP, Ui.WRAP, 2, 2, 0, 0));
        }
    }

    private View fileRow(final int index) {
        final Tool.InFile f = files.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        row.setBackground(Ui.shape(Theme.field, 14));

        final ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = Ui.shape(0xFFFFFFFF, 8);
        thumb.setBackground(tb);
        thumb.setClipToOutline(true);
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(56)));
        loadThumb(thumb, f);

        LinearLayout col2 = new LinearLayout(this);
        col2.setOrientation(LinearLayout.VERTICAL);
        col2.addView(Ui.ellipsized(Ui.text(this, f.name, 14.5f, Theme.text, true), 1));
        String sub = Storage.human(f.size);
        if (f.encrypted) {
            sub = "Password protected · " + sub;
        } else if (f.pages > 0) {
            sub = f.pages + (f.pages == 1 ? " page · " : " pages · ") + sub;
        }
        col2.addView(Ui.text(this, sub, 12.5f, Theme.muted, false), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        cl.setMargins(Ui.dp(10), 0, Ui.dp(6), 0);
        row.addView(col2, cl);

        if (tool.ordered && files.size() > 1) {
            if (index > 0) {
                row.addView(smallBtn(R.drawable.ic_up, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        swap(index, index - 1);
                    }
                }));
            }
            if (index < files.size() - 1) {
                row.addView(smallBtn(R.drawable.ic_down, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        swap(index, index + 1);
                    }
                }));
            }
        }
        row.addView(smallBtn(R.drawable.ic_close, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                removeFile(index);
            }
        }));
        return row;
    }

    private View smallBtn(int icon, View.OnClickListener l) {
        FrameLayout b = Ui.roundIconButton(this, icon, Theme.text, Theme.surface, 34);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(34), Ui.dp(34));
        lp.setMargins(Ui.dp(4), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void loadThumb(final ImageView iv, final Tool.InFile f) {
        if (f.encrypted) {
            iv.setImageResource(R.drawable.ic_protect);
            iv.setColorFilter(Theme.PURPLE);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            return;
        }
        final boolean pdf = tool.kind == Tool.Kind.PDF;
        try {
            thumbs.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap b = null;
                    try {
                        if (pdf) {
                            PdfPages pp = new PdfPages(f.file);
                            try {
                                b = pp.renderWidth(0, 140);
                            } finally {
                                pp.close();
                            }
                        } else {
                            b = PdfOps.decodeImage(f, 160);
                        }
                    } catch (Throwable ignored) {
                        // thumbnail is optional
                    }
                    final Bitmap bm = b;
                    if (bm != null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                iv.setImageBitmap(bm);
                            }
                        });
                    }
                }
            });
        } catch (Exception ignored) {
            // executor already shut down
        }
    }

    private void swap(int a, int b) {
        Tool.InFile t = files.get(a);
        files.set(a, files.get(b));
        files.set(b, t);
        renderFiles();
    }

    private void removeFile(int i) {
        Tool.InFile f = files.remove(i);
        //noinspection ResultOfMethodCallIgnored
        f.file.delete();
        if (!tool.multi) resetExtras();
        renderFiles();
        refreshState();
    }

    private void pick() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(tool.kind == Tool.Kind.IMAGES ? "image/*" : "application/pdf");
        if (tool.multi) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available on this device", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_PICK) {
            List<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) importFiles(uris);
        } else if (requestCode == REQ_PAGES) {
            if (tool.extra == Tool.Extra.PAGES_SELECT) {
                int[] sel = data.getIntArrayExtra("sel");
                selection = sel == null ? new int[0] : sel;
            } else if (tool.extra == Tool.Extra.ORGANIZE) {
                orgSrc = data.getIntArrayExtra("src");
                orgRot = data.getIntArrayExtra("rot");
            }
            updateSummaries();
        } else if (requestCode == REQ_SIGN) {
            sigPath = data.getStringExtra("sig");
            sigPage = data.getIntExtra("page", 0);
            sigX = data.getFloatExtra("x", 0.7f);
            sigY = data.getFloatExtra("y", 0.85f);
            sigW = data.getFloatExtra("w", 0.3f);
            String a = data.getStringExtra("apply");
            sigApply = a == null ? "current" : a;
            updateSummaries();
        }
    }

    private void importFiles(final List<Uri> uris) {
        showBusy(true, "Reading files…");
        final boolean pdf = tool.kind == Tool.Kind.PDF;
        final boolean allowEnc = tool.id.equals("unlock");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Tool.InFile> ok = new ArrayList<>();
                final List<String> errors = new ArrayList<>();
                for (Uri u : uris) {
                    try {
                        ok.add(Storage.importFile(app, u, pdf, allowEnc));
                    } catch (Tool.UserError e) {
                        errors.add(e.getMessage());
                    } catch (Throwable e) {
                        errors.add("Could not read one of the files.");
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showBusy(false, null);
                        if (!errors.isEmpty()) {
                            Toast.makeText(ToolActivity.this, errors.get(0), Toast.LENGTH_LONG).show();
                        }
                        if (ok.isEmpty()) return;
                        if (tool.multi) {
                            files.addAll(ok);
                        } else {
                            for (Tool.InFile old : files) {
                                //noinspection ResultOfMethodCallIgnored
                                old.file.delete();
                            }
                            files.clear();
                            files.add(ok.get(0));
                            resetExtras();
                            onSingleFileReady(ok.get(0));
                        }
                        renderFiles();
                        refreshState();
                        updateSummaries();
                    }
                });
            }
        }).start();
    }

    private void resetExtras() {
        selection = new int[0];
        orgSrc = null;
        orgRot = null;
        sigPath = null;
        sigPage = 0;
        updateSummaries();
    }

    /** Hook for tools that need to read the freshly chosen document. */
    private void onSingleFileReady(final Tool.InFile f) {
        if (tool.extra != Tool.Extra.METADATA || f.encrypted) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Map<String, String> m = new HashMap<>();
                PDDocument d = null;
                try {
                    d = PdfOps.open(f);
                    PDDocumentInformation in = d.getDocumentInformation();
                    m.put("title", in.getTitle());
                    m.put("author", in.getAuthor());
                    m.put("subject", in.getSubject());
                    m.put("keywords", in.getKeywords());
                    m.put("creator", in.getCreator());
                    m.put("producer", in.getProducer());
                } catch (Exception ignored) {
                    // leave the fields empty
                } finally {
                    PdfOps.closeQuiet(d);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        for (Map.Entry<String, EditText> e : metaInputs.entrySet()) {
                            String v = m.get(e.getKey());
                            e.getValue().setText(v == null ? "" : v);
                        }
                    }
                });
            }
        }).start();
    }

    /* ===================================================================== *
     *  Tool-specific sections
     * ===================================================================== */
    private LinearLayout extraCard(String title, String subtitle) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, title, 16, Theme.text, true));
        if (subtitle != null) {
            card.addView(Ui.text(this, subtitle, 13, Theme.muted, false), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0));
        }
        extrasBox.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        return card;
    }

    private void buildExtras() {
        switch (tool.extra) {
            case PAGES_SELECT: {
                LinearLayout card = extraCard("Pages", null);
                selSummary = Ui.text(this, "", 14, Theme.muted, false);
                card.addView(selSummary, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 6, 0, 12));
                TextView b = Ui.softButton(this, "Choose pages");
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPages("select");
                    }
                });
                card.addView(b, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
                break;
            }
            case ORGANIZE: {
                LinearLayout card = extraCard("Page order", null);
                orgSummary = Ui.text(this, "", 14, Theme.muted, false);
                card.addView(orgSummary, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 6, 0, 12));
                TextView b = Ui.softButton(this, "Arrange pages");
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPages("organize");
                    }
                });
                card.addView(b, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
                break;
            }
            case SIGN: {
                LinearLayout card = extraCard("Signature", null);
                sigSummary = Ui.text(this, "", 14, Theme.muted, false);
                card.addView(sigSummary, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 6, 0, 12));
                TextView b = Ui.softButton(this, "Create & place signature");
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSign();
                    }
                });
                card.addView(b, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
                break;
            }
            case TEXT: {
                LinearLayout card = extraCard("Your text", "Type or paste anything — any language.");
                textInput = Ui.input(this, "Start typing…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 8);
                card.addView(textInput, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
                break;
            }
            case METADATA: {
                LinearLayout card = extraCard("Document properties", "Edit the fields, or tick the box to remove them all.");
                String[][] fields = {{"title", "Title"}, {"author", "Author"}, {"subject", "Subject"},
                        {"keywords", "Keywords (comma separated)"}, {"creator", "Created with"}, {"producer", "Producer"}};
                for (String[] f : fields) {
                    card.addView(Ui.text(this, f[1], 12.5f, Theme.muted, true), Ui.lp(Ui.WRAP, Ui.WRAP, 2, 12, 0, 5));
                    EditText e = Ui.input(this, "", InputType.TYPE_CLASS_TEXT, 1);
                    metaInputs.put(f[0], e);
                    card.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
                }
                metaClear = new Switch(this);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(Ui.text(this, "Remove all properties instead", 14, Theme.text, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                row.addView(metaClear);
                card.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
                break;
            }
            default:
                break;
        }
        updateSummaries();
    }

    private void updateSummaries() {
        int total = files.isEmpty() ? 0 : Math.max(0, files.get(0).pages);
        if (selSummary != null) {
            if (selection.length == 0) {
                selSummary.setText("Nothing selected yet — tap “Choose pages”.");
                selSummary.setTextColor(Theme.muted);
            } else {
                selSummary.setText(selection.length + " of " + total + " pages selected\n" + rangeText(selection));
                selSummary.setTextColor(Theme.text);
            }
        }
        if (orgSummary != null) {
            if (orgSrc == null) {
                orgSummary.setText(total + " pages in their original order. Tap to reorder, rotate or delete pages.");
            } else {
                orgSummary.setText("Custom layout ready: " + orgSrc.length + (orgSrc.length == 1 ? " page" : " pages") + " in the new document.");
                orgSummary.setTextColor(Theme.text);
            }
        }
        if (sigSummary != null) {
            if (sigPath == null) {
                sigSummary.setText("Draw or type your signature, then drag it to the right spot on the page.");
                sigSummary.setTextColor(Theme.muted);
            } else {
                String where = "all".equals(sigApply) ? "every page" : "first".equals(sigApply) ? "the first page"
                        : "last".equals(sigApply) ? "the last page" : "page " + (sigPage + 1);
                sigSummary.setText("Signature ready for " + where + ". Tap to change it.");
                sigSummary.setTextColor(Theme.text);
            }
        }
    }

    private static String rangeText(int[] sel) {
        int[] s = sel.clone();
        Arrays.sort(s);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length) {
            int j = i;
            while (j + 1 < s.length && s[j + 1] == s[j] + 1) j++;
            if (sb.length() > 0) sb.append(", ");
            sb.append(j > i ? (s[i] + 1) + "–" + (s[j] + 1) : String.valueOf(s[i] + 1));
            i = j + 1;
            if (sb.length() > 80) {
                sb.append(" …");
                break;
            }
        }
        return sb.toString();
    }

    private void openPages(String mode) {
        if (files.isEmpty()) return;
        Tool.InFile f = files.get(0);
        Intent i = new Intent(this, PagesActivity.class);
        i.putExtra("path", f.file.getAbsolutePath());
        i.putExtra("pages", f.pages);
        i.putExtra("mode", mode);
        i.putExtra("verb", tool.id.equals("remove") ? "remove" : "extract");
        i.putExtra("title", tool.name);
        i.putExtra("c1", tool.c1);
        i.putExtra("c2", tool.c2);
        if (mode.equals("select")) {
            i.putExtra("sel", selection);
        } else if (orgSrc != null) {
            i.putExtra("src", orgSrc);
            i.putExtra("rot", orgRot);
        }
        startActivityForResult(i, REQ_PAGES);
    }

    private void openSign() {
        if (files.isEmpty()) return;
        Tool.InFile f = files.get(0);
        Intent i = new Intent(this, SignActivity.class);
        i.putExtra("path", f.file.getAbsolutePath());
        i.putExtra("pages", f.pages);
        i.putExtra("c1", tool.c1);
        i.putExtra("c2", tool.c2);
        startActivityForResult(i, REQ_SIGN);
    }

    /* ===================================================================== *
     *  Options
     * ===================================================================== */
    private abstract static class Watch implements TextWatcher {
        abstract void changed(String s);

        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {
        }

        @Override
        public void afterTextChanged(Editable e) {
            changed(e.toString());
        }
    }

    private void buildOptions() {
        if (tool.opts.isEmpty()) return;
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Options", 16, Theme.text, true));
        for (Tool.Opt o : tool.opts) {
            values.put(o.key, o.def);
            View v = optionView(o);
            optViews.add(new OptView(o, v));
            card.addView(v, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 0));
        }
        optionsBox.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        applyVisibility();
    }

    private void applyVisibility() {
        for (OptView ov : optViews) {
            if (ov.opt.showKey == null) continue;
            String cur = values.get(ov.opt.showKey);
            boolean show = false;
            for (String v : ov.opt.showVals.split("\\|")) {
                if (v.equals(cur)) show = true;
            }
            ov.view.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private View optionView(final Tool.Opt o) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        switch (o.type) {
            case SWITCH: {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(Ui.text(this, o.label, 14.5f, Theme.text, false), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                Switch sw = new Switch(this);
                sw.setChecked("1".equals(o.def));
                sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        values.put(o.key, checked ? "1" : "0");
                        applyVisibility();
                    }
                });
                row.addView(sw);
                box.addView(row);
                break;
            }
            case SLIDER: {
                LinearLayout head = new LinearLayout(this);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.addView(Ui.text(this, o.label, 12.5f, Theme.muted, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                final TextView val = Ui.text(this, o.def + o.unit, 13.5f, Theme.text, true);
                head.addView(val);
                box.addView(head);
                SeekBar sb = new SeekBar(this);
                sb.setMax(o.max - o.min);
                int def;
                try {
                    def = Integer.parseInt(o.def);
                } catch (NumberFormatException e) {
                    def = o.min;
                }
                sb.setProgress(def - o.min);
                sb.setProgressTintList(ColorStateList.valueOf(Theme.PINK));
                sb.setThumbTintList(ColorStateList.valueOf(Theme.PINK));
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        int v = p + o.min;
                        values.put(o.key, String.valueOf(v));
                        val.setText(v + o.unit);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar s) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar s) {
                    }
                });
                box.addView(sb, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 6, 0, 0));
                break;
            }
            case SELECT: {
                box.addView(Ui.text(this, o.label, 12.5f, Theme.muted, true), Ui.lp(Ui.WRAP, Ui.WRAP, 2, 0, 0, 6));
                LinearLayout field = new LinearLayout(this);
                field.setOrientation(LinearLayout.HORIZONTAL);
                field.setGravity(Gravity.CENTER_VERTICAL);
                field.setPadding(Ui.dp(14), Ui.dp(13), Ui.dp(12), Ui.dp(13));
                field.setBackground(Ui.ripple(Ui.stroked(Theme.field, Theme.stroke, 14, 1), 14));
                field.setClickable(true);
                final TextView cur = Ui.text(this, choiceLabel(o, o.def), 15, Theme.text, false);
                field.addView(cur, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
                ImageView chev = new ImageView(this);
                chev.setImageResource(R.drawable.ic_down);
                chev.setColorFilter(Theme.muted);
                field.addView(chev, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
                field.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chooseDialog(o, cur);
                    }
                });
                box.addView(field);
                break;
            }
            case COLOR: {
                box.addView(Ui.text(this, o.label, 12.5f, Theme.muted, true), Ui.lp(Ui.WRAP, Ui.WRAP, 2, 0, 0, 8));
                final LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (final String[] c : o.choices) {
                    final FrameLayout sw = new FrameLayout(this);
                    sw.setTag(c[0]);
                    sw.setClickable(true);
                    sw.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            values.put(o.key, c[0]);
                            paintSwatches(row, o);
                        }
                    });
                    row.addView(sw, Ui.lp(Ui.dp(38), Ui.dp(38), 0, 0, 10, 0));
                }
                box.addView(row);
                paintSwatches(row, o);
                break;
            }
            default: { // TEXT, NUMBER, PASSWORD
                box.addView(Ui.text(this, o.label, 12.5f, Theme.muted, true), Ui.lp(Ui.WRAP, Ui.WRAP, 2, 0, 0, 6));
                int type = InputType.TYPE_CLASS_TEXT;
                if (o.type == Tool.OptType.NUMBER) {
                    type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                } else if (o.type == Tool.OptType.PASSWORD) {
                    type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
                }
                EditText e = Ui.input(this, o.type == Tool.OptType.PASSWORD ? "Enter password" : "", type, 1);
                e.setText(o.def);
                e.addTextChangedListener(new Watch() {
                    @Override
                    void changed(String s) {
                        values.put(o.key, s);
                    }
                });
                box.addView(e, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
                break;
            }
        }
        if (o.hint != null && !o.hint.isEmpty()) {
            TextView h = Ui.text(this, o.hint, 12, Theme.muted, false);
            h.setLineSpacing(0, 1.1f);
            box.addView(h, Ui.lp(Ui.WRAP, Ui.WRAP, 2, 6, 0, 0));
        }
        return box;
    }

    private static String choiceLabel(Tool.Opt o, String value) {
        for (String[] c : o.choices) {
            if (c[0].equals(value)) return c[1];
        }
        return value;
    }

    private void chooseDialog(final Tool.Opt o, final TextView target) {
        final String[] labels = new String[o.choices.length];
        int checked = 0;
        String cur = values.get(o.key);
        for (int i = 0; i < labels.length; i++) {
            labels[i] = o.choices[i][1];
            if (o.choices[i][0].equals(cur)) checked = i;
        }
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(o.label)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        values.put(o.key, o.choices[which][0]);
                        target.setText(o.choices[which][1]);
                        applyVisibility();
                        d.dismiss();
                    }
                }).show();
    }

    private void paintSwatches(LinearLayout row, Tool.Opt o) {
        String cur = values.get(o.key);
        for (int i = 0; i < row.getChildCount(); i++) {
            FrameLayout sw = (FrameLayout) row.getChildAt(i);
            String hex = (String) sw.getTag();
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            int color;
            try {
                color = Color.parseColor(hex);
            } catch (IllegalArgumentException e) {
                color = Color.GRAY;
            }
            g.setColor(color);
            boolean on = hex.equals(cur);
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

    /* ===================================================================== *
     *  Run
     * ===================================================================== */
    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void runTool() {
        if (busy) return;
        if (tool.kind != Tool.Kind.NONE && files.size() < tool.minFiles) {
            toast(files.isEmpty() ? "Choose a file first" : "Add at least " + tool.minFiles + " files");
            return;
        }
        final Tool.Ctx c = new Tool.Ctx();
        c.app = getApplicationContext();
        c.files = new ArrayList<>(files);
        c.opts = new HashMap<>(values);

        switch (tool.extra) {
            case PAGES_SELECT:
                if (selection.length == 0) {
                    toast("Choose at least one page first");
                    return;
                }
                c.selection = selection.clone();
                break;
            case ORGANIZE: {
                int total = Math.max(0, files.get(0).pages);
                if (orgSrc == null) {
                    orgSrc = new int[total];
                    orgRot = new int[total];
                    for (int i = 0; i < total; i++) orgSrc[i] = i;
                }
                c.orgSrc = orgSrc.clone();
                c.orgRot = orgRot.clone();
                break;
            }
            case SIGN:
                if (sigPath == null) {
                    toast("Create your signature first");
                    return;
                }
                c.sigPath = sigPath;
                c.sigPage = sigPage;
                c.sigX = sigX;
                c.sigY = sigY;
                c.sigW = sigW;
                c.sigApply = sigApply;
                break;
            case TEXT:
                c.text = textInput.getText().toString();
                if (c.text.trim().isEmpty()) {
                    toast("Type or paste some text first");
                    return;
                }
                break;
            case METADATA: {
                Map<String, String> m = new HashMap<>();
                for (Map.Entry<String, EditText> e : metaInputs.entrySet()) {
                    m.put(e.getKey(), e.getValue().getText().toString());
                }
                c.meta = m;
                c.metaClear = metaClear.isChecked();
                break;
            }
            default:
                break;
        }

        c.progress = new Tool.Progress() {
            @Override
            public void update(final float f, final String label) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        overlayBar.setProgress((int) (f * 1000));
                        if (label != null && !label.isEmpty()) overlayText.setText(label);
                    }
                });
            }
        };

        showBusy(true, "Working…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Tool.Result res = null;
                String error = null;
                try {
                    res = tool.runner.run(c);
                    if (res != null) {
                        long now = System.currentTimeMillis();
                        for (Tool.OutFile o : res.outs) {
                            Storage.Recent r = new Storage.Recent();
                            r.name = o.name;
                            r.path = o.file.getAbsolutePath();
                            r.mime = o.mime;
                            r.tool = tool.name;
                            r.uri = "";
                            r.time = now;
                            r.size = o.file.length();
                            Storage.addRecent(getApplicationContext(), r);
                        }
                    }
                } catch (Tool.UserError e) {
                    error = e.getMessage();
                } catch (OutOfMemoryError e) {
                    error = "This file is too large for your phone's memory. Try a smaller file or lower quality.";
                } catch (Throwable e) {
                    error = "Something went wrong" + (e.getMessage() == null ? "." : ": " + e.getMessage());
                }
                final Tool.Result fr = res;
                final String fe = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showBusy(false, null);
                        if (fe != null || fr == null) {
                            showError(fe == null ? "Nothing was created." : fe);
                        } else {
                            showResult(fr);
                        }
                    }
                });
            }
        }).start();
    }

    private void showError(String msg) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, Ui.dialogTheme());
        b.setTitle("That didn’t work").setMessage(msg).setPositiveButton("OK", null).show();
    }

    /* ===================================================================== *
     *  Result
     * ===================================================================== */
    private void showResult(final Tool.Result r) {
        showingResult = true;
        refreshState();
        resultBox.removeAllViews();

        LinearLayout card = Ui.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout ok = new FrameLayout(this);
        GradientDrawable okBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF10B981, 0xFF22D3EE});
        okBg.setShape(GradientDrawable.OVAL);
        ok.setBackground(okBg);
        ImageView ck = new ImageView(this);
        ck.setImageResource(R.drawable.ic_check);
        ck.setColorFilter(Color.WHITE);
        ok.addView(ck, new FrameLayout.LayoutParams(Ui.dp(32), Ui.dp(32), Gravity.CENTER));
        card.addView(ok, new LinearLayout.LayoutParams(Ui.dp(64), Ui.dp(64)));
        card.addView(Ui.text(this, r.outs.size() > 1 ? r.outs.size() + " files are ready" : "Your file is ready", 20, Theme.text, true),
                Ui.lp(Ui.WRAP, Ui.WRAP, 0, 14, 0, 0));
        if (r.note != null && !r.note.isEmpty()) {
            TextView n = Ui.text(this, r.note, 14, Theme.muted, false);
            n.setGravity(Gravity.CENTER);
            n.setLineSpacing(0, 1.15f);
            card.addView(n, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 6, 0, 0));
        }

        // first-page preview
        Tool.OutFile firstPdf = null;
        for (Tool.OutFile o : r.outs) {
            if ("application/pdf".equals(o.mime)) {
                firstPdf = o;
                break;
            }
        }
        if (firstPdf != null) {
            final ImageView pv = new ImageView(this);
            pv.setAdjustViewBounds(true);
            pv.setBackgroundColor(Color.WHITE);
            pv.setVisibility(View.GONE);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ui.WRAP, Ui.dp(300));
            plp.setMargins(0, Ui.dp(16), 0, 0);
            card.addView(pv, plp);
            final File pdf = firstPdf.file;
            try {
                thumbs.execute(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap b = null;
                        try {
                            PdfPages pp = new PdfPages(pdf);
                            try {
                                b = pp.renderWidth(0, 600);
                            } finally {
                                pp.close();
                            }
                        } catch (Throwable ignored) {
                            // preview is optional
                        }
                        final Bitmap bm = b;
                        if (bm != null) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    pv.setImageBitmap(bm);
                                    pv.setVisibility(View.VISIBLE);
                                }
                            });
                        }
                    }
                });
            } catch (Exception ignored) {
                // executor stopped
            }
        }
        resultBox.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

        // extracted text
        if (r.text != null && !r.text.isEmpty()) {
            LinearLayout tc = Ui.card(this);
            tc.addView(Ui.text(this, "Text preview", 16, Theme.text, true));
            final String full = r.text;
            TextView tv = Ui.text(this, full.length() > 4000 ? full.substring(0, 4000) + "\n…" : full, 13.5f, Theme.text, false);
            tv.setTextIsSelectable(true);
            tv.setMaxLines(16);
            tv.setLineSpacing(0, 1.15f);
            tv.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
            tv.setBackground(Ui.shape(Theme.field, 12));
            tc.addView(tv, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 10, 0, 10));
            TextView copy = Ui.softButton(this, "Copy all text");
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("WebyPDF text", full));
                        Toast.makeText(ToolActivity.this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            tc.addView(copy, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            resultBox.addView(tc, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));
        }

        // output files
        LinearLayout list = Ui.card(this);
        list.addView(Ui.text(this, r.outs.size() > 1 ? "Your files" : "File", 16, Theme.text, true));
        for (final Tool.OutFile o : r.outs) {
            list.addView(outRow(o), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        }
        if (r.outs.size() > 1) {
            LinearLayout both = new LinearLayout(this);
            both.setOrientation(LinearLayout.HORIZONTAL);
            TextView shareAll = Ui.softButton(this, "Share all");
            shareAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    List<File> fs = new ArrayList<>();
                    List<String> ms = new ArrayList<>();
                    for (Tool.OutFile o : r.outs) {
                        fs.add(o.file);
                        ms.add(o.mime);
                    }
                    Storage.share(ToolActivity.this, fs, ms);
                }
            });
            TextView saveAll = Ui.primaryButton(this, "Save all");
            saveAll.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
            saveAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveOuts(r.outs);
                }
            });
            both.addView(shareAll, Ui.weight(1, 0, 0, 6, 0));
            both.addView(saveAll, Ui.weight(1, 6, 0, 0, 0));
            list.addView(both, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        }
        resultBox.addView(list, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 14));

        // next steps
        LinearLayout next = new LinearLayout(this);
        next.setOrientation(LinearLayout.HORIZONTAL);
        TextView again = Ui.softButton(this, "Start over");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recreate();
            }
        });
        TextView home = Ui.primaryButton(this, "Done");
        home.setPadding(Ui.dp(16), Ui.dp(13), Ui.dp(16), Ui.dp(13));
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        next.addView(again, Ui.weight(1, 0, 0, 6, 0));
        next.addView(home, Ui.weight(1, 6, 0, 0, 0));
        resultBox.addView(next, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, 0);
            }
        });
    }

    private View outRow(final Tool.OutFile o) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.shape(Theme.field, 14));
        row.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int icon = R.drawable.ic_file_pdf;
        int c1 = 0xFFEF4444;
        int c2 = 0xFFF97316;
        if (o.mime.startsWith("image/")) {
            icon = R.drawable.ic_image;
            c1 = 0xFFA855F7;
            c2 = 0xFF6366F1;
        } else if (o.mime.startsWith("text/")) {
            icon = R.drawable.ic_pdf_to_text;
            c1 = 0xFF14B8A6;
            c2 = 0xFF22C55E;
        }
        top.addView(Ui.iconTile(this, icon, c1, c2, 40, 12));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(Ui.ellipsized(Ui.text(this, o.name, 14.5f, Theme.text, true), 1));
        info.addView(Ui.text(this, Storage.human(o.file.length()), 12.5f, Theme.muted, false), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        il.setMargins(Ui.dp(10), 0, 0, 0);
        top.addView(info, il);
        row.addView(top);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView open = Ui.softButton(this, "Open");
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Storage.open(ToolActivity.this, o.file, o.mime);
            }
        });
        TextView share = Ui.softButton(this, "Share");
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<File> fs = new ArrayList<>();
                List<String> ms = new ArrayList<>();
                fs.add(o.file);
                ms.add(o.mime);
                Storage.share(ToolActivity.this, fs, ms);
            }
        });
        TextView save = Ui.softButton(this, "Save");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<Tool.OutFile> one = new ArrayList<>();
                one.add(o);
                saveOuts(one);
            }
        });
        btns.addView(open, Ui.weight(1, 0, 0, 4, 0));
        btns.addView(share, Ui.weight(1, 4, 0, 4, 0));
        btns.addView(save, Ui.weight(1, 4, 0, 0, 0));
        row.addView(btns, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        return row;
    }

    private void saveOuts(final List<Tool.OutFile> outs) {
        showBusy(true, "Saving to Downloads…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    for (Tool.OutFile o : outs) {
                        Storage.saveToDownloads(app, o.file, o.name, o.mime);
                    }
                    msg = outs.size() == 1 ? "Saved to Downloads/" + Storage.SUBDIR
                            : outs.size() + " files saved to Downloads/" + Storage.SUBDIR;
                } catch (Exception e) {
                    msg = "Could not save: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showBusy(false, null);
                        Toast.makeText(ToolActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
