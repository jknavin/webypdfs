package com.webypdf.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Home screen with three tabs: Home (search + tools), Files (recent results) and Settings.
 * The whole UI is built in code with the helpers in {@link Ui}.
 */
public class MainActivity extends Activity {

    private static final int TAB_HOME = 0;
    private static final int TAB_FILES = 1;
    private static final int TAB_SETTINGS = 2;

    private static int lastTab = TAB_HOME;

    private FrameLayout content;
    private final View[] tabViews = new View[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private int tab = TAB_HOME;

    // home state
    private String query = "";
    private String category = "all";
    private LinearLayout homeSections;
    private LinearLayout homeRecent;
    private LinearLayout chipRow;
    private LinearLayout filesList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme.apply(this);
        Theme.window(this, Theme.PURPLE, Theme.nav, !Theme.dark);
        Storage.cleanCache(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        root.addView(buildNav(), new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        setContentView(root);

        tabViews[TAB_HOME] = buildHome();
        tabViews[TAB_FILES] = buildFiles();
        tabViews[TAB_SETTINGS] = buildSettings();
        for (View v : tabViews) {
            v.setVisibility(View.GONE);
            content.addView(v, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        select(lastTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecents();
    }

    @Override
    public void onBackPressed() {
        if (tab != TAB_HOME) {
            select(TAB_HOME);
        } else {
            super.onBackPressed();
        }
    }

    /* ===================================================================== *
     *  Bottom navigation
     * ===================================================================== */
    private View buildNav() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Theme.nav);
        wrap.addView(Ui.divider(this));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(8));
        int[] icons = {R.drawable.ic_home, R.drawable.ic_recent, R.drawable.ic_settings};
        String[] labels = {"Home", "Files", "Settings"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(6), 0, Ui.dp(4));
            item.setBackground(Ui.ripple(Ui.shape(Color.TRANSPARENT, 16), 16));
            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                }
            });
            ImageView ic = new ImageView(this);
            ic.setImageResource(icons[i]);
            item.addView(ic, new LinearLayout.LayoutParams(Ui.dp(24), Ui.dp(24)));
            TextView lb = Ui.text(this, labels[i], 12, Theme.muted, true);
            item.addView(lb, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0));
            navIcons[i] = ic;
            navLabels[i] = lb;
            bar.addView(item, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        }
        wrap.addView(bar);
        return wrap;
    }

    private void select(int t) {
        tab = t;
        lastTab = t;
        int active = Theme.dark ? 0xFFB69CFF : Theme.PURPLE;
        for (int i = 0; i < 3; i++) {
            tabViews[i].setVisibility(i == t ? View.VISIBLE : View.GONE);
            navIcons[i].setColorFilter(i == t ? active : Theme.muted);
            navLabels[i].setTextColor(i == t ? active : Theme.muted);
        }
        if (t == TAB_FILES) {
            refreshRecents();
        }
    }

    /* ===================================================================== *
     *  HOME
     * ===================================================================== */
    private View buildHome() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));

        /* ---- gradient header ---- */
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(Ui.dp(20), Ui.dp(22), Ui.dp(20), Ui.dp(24));
        head.setBackground(Ui.headerBg(0xFF6D28D9, 0xFFDB2777, 32));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        brandRow.addView(logo, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(Ui.text(this, "WebyPDF", 26, Color.WHITE, true));
        names.addView(Ui.text(this, "Every PDF tool you need", 13.5f, 0xDDFFFFFF, false), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        nlp.setMargins(Ui.dp(12), 0, 0, 0);
        brandRow.addView(names, nlp);
        head.addView(brandRow);

        // badges
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.addView(badge(R.drawable.ic_shield, "Private"));
        badges.addView(badge(R.drawable.ic_offline, "Works offline"));
        badges.addView(badge(R.drawable.ic_star, "Free"));
        head.addView(badges, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 16, 0, 0));

        // search
        LinearLayout search = new LinearLayout(this);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(Ui.dp(14), 0, Ui.dp(8), 0);
        search.setBackground(Ui.shape(0xF2FFFFFF, 16));
        ImageView si = new ImageView(this);
        si.setImageResource(R.drawable.ic_search);
        si.setColorFilter(0xFF6B6491);
        search.addView(si, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Search " + Tools.ALL.size() + " PDF tools");
        et.setHintTextColor(0xFF8B84AE);
        et.setTextColor(0xFF1B1340);
        et.setTextSize(15.5f);
        et.setBackgroundColor(Color.TRANSPARENT);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(Ui.dp(10), Ui.dp(14), Ui.dp(10), Ui.dp(14));
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                rebuildSections();
            }
        });
        search.addView(et, new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
        head.addView(search, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 16, 0, 0));
        col.addView(head);

        /* ---- category chips ---- */
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(Ui.dp(16), Ui.dp(2), Ui.dp(16), Ui.dp(2));
        hs.addView(chipRow, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
        col.addView(hs, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 18, 0, 4));
        rebuildChips();

        /* ---- recent (mini) + sections ---- */
        homeRecent = new LinearLayout(this);
        homeRecent.setOrientation(LinearLayout.VERTICAL);
        homeRecent.setPadding(Ui.dp(16), 0, Ui.dp(16), 0);
        col.addView(homeRecent);

        homeSections = new LinearLayout(this);
        homeSections.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeSections, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        rebuildSections();

        col.addView(Ui.spacer(this, 24));
        return sv;
    }

    private View badge(int icon, String label) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(12), Ui.dp(6));
        b.setBackground(Ui.shape(0x33FFFFFF, 20));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(Color.WHITE);
        b.addView(iv, new LinearLayout.LayoutParams(Ui.dp(14), Ui.dp(14)));
        b.addView(Ui.text(this, label, 12, Color.WHITE, true), Ui.lp(Ui.WRAP, Ui.WRAP, 6, 0, 0, 0));
        b.setLayoutParams(Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
        return b;
    }

    private void rebuildChips() {
        chipRow.removeAllViews();
        for (final String[] c : Tools.CATS) {
            TextView chip = Ui.chip(this, c[1], c[0].equals(category));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    category = c[0];
                    rebuildChips();
                    rebuildSections();
                }
            });
            chipRow.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
        }
    }

    private void rebuildSections() {
        homeSections.removeAllViews();
        boolean browsing = query.isEmpty() && category.equals("all");

        // popular row
        if (browsing) {
            TextView t = Ui.sectionTitle(this, "Popular tools");
            homeSections.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP, 20, 14, 0, 10));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(16), 0, Ui.dp(8), 0);
            for (Tool tool : Tools.ALL) {
                if (tool.popular) {
                    row.addView(popularCard(tool), Ui.lp(Ui.dp(148), Ui.WRAP, 0, 0, 10, 0));
                }
            }
            hs.addView(row, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP));
            homeSections.addView(hs);
        }

        // filtered list
        List<Tool> list = new ArrayList<>();
        for (Tool tool : Tools.ALL) {
            if (!category.equals("all") && !tool.cat.equals(category)) continue;
            if (!query.isEmpty() && !(tool.name + " " + tool.desc + " " + tool.cat).toLowerCase(Locale.ROOT).contains(query)) continue;
            list.add(tool);
        }
        String title = !query.isEmpty() ? "Results" : category.equals("all") ? "All tools" : categoryName();
        homeSections.addView(Ui.sectionTitle(this, title), Ui.lp(Ui.WRAP, Ui.WRAP, 20, 22, 0, 10));

        if (list.isEmpty()) {
            TextView empty = Ui.text(this, "No tools match “" + query + "”.\nTry “merge”, “compress” or “sign”.", 14, Theme.muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.2f);
            empty.setPadding(Ui.dp(24), Ui.dp(28), Ui.dp(24), Ui.dp(28));
            homeSections.addView(empty, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
            return;
        }
        for (int i = 0; i < list.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            row.setPadding(Ui.dp(10), 0, Ui.dp(10), 0);
            row.addView(toolCard(list.get(i)), cardLp());
            if (i + 1 < list.size()) {
                row.addView(toolCard(list.get(i + 1)), cardLp());
            } else {
                View filler = new View(this);
                row.addView(filler, cardLp());
            }
            homeSections.addView(row, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        }
    }

    private String categoryName() {
        for (String[] c : Tools.CATS) {
            if (c[0].equals(category)) return c[1] + " tools";
        }
        return "Tools";
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.MATCH, 1f);
        p.setMargins(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6));
        return p;
    }

    private View toolCard(final Tool t) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(14));
        card.setBackground(Ui.ripple(Ui.stroked(Theme.surface, Theme.stroke, 20, 1), 20));
        if (!Theme.dark) {
            card.setElevation(Ui.dp(2));
        }
        card.setClickable(true);
        card.addView(Ui.iconTile(this, t.icon, t.c1, t.c2, 46, 14));
        card.addView(Ui.text(this, t.name, 15, Theme.text, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 12, 0, 0));
        TextView d = Ui.ellipsized(Ui.text(this, t.desc, 12.5f, Theme.muted, false), 2);
        d.setLineSpacing(0, 1.1f);
        card.addView(d, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTool(t);
            }
        });
        return card;
    }

    private View popularCard(final Tool t) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(14), Ui.dp(14), Ui.dp(14), Ui.dp(16));
        card.setBackground(Ui.ripple(Ui.gradient(t.c1, t.c2, 22), 22));
        card.setClickable(true);
        FrameLayout bubble = new FrameLayout(this);
        bubble.setBackground(Ui.shape(0x33FFFFFF, 14));
        ImageView iv = new ImageView(this);
        iv.setImageResource(t.icon);
        iv.setColorFilter(Color.WHITE);
        bubble.addView(iv, new FrameLayout.LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.CENTER));
        card.addView(bubble, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        card.addView(Ui.text(this, t.name, 16, Color.WHITE, true), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 14, 0, 0));
        TextView d = Ui.ellipsized(Ui.text(this, t.desc, 12, 0xDDFFFFFF, false), 2);
        card.addView(d, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 4, 0, 0));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTool(t);
            }
        });
        return card;
    }

    private void openTool(Tool t) {
        Intent i = new Intent(this, ToolActivity.class);
        i.putExtra("tool", t.id);
        startActivity(i);
    }

    /* ===================================================================== *
     *  FILES (recent results)
     * ===================================================================== */
    private View buildFiles() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        TextView clear = Ui.text(this, "Clear all", 13, Color.WHITE, true);
        clear.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        clear.setBackground(Ui.shape(0x33FFFFFF, 18));
        clear.setClickable(true);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        LinearLayout hdr = Ui.header(this, "Recent files", "Results you created with WebyPDF", 0xFF0EA5E9, 0xFF6366F1, clear);
        // no back arrow on a tab root
        hdr.getChildAt(0).setVisibility(View.GONE);
        page.addView(hdr);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        filesList = new LinearLayout(this);
        filesList.setOrientation(LinearLayout.VERTICAL);
        filesList.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(24));
        sv.addView(filesList, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        page.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        return page;
    }

    private void confirmClear() {
        if (Storage.recents(this).isEmpty()) return;
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle("Delete all recent files?")
                .setMessage("This removes the results kept inside WebyPDF. Copies you saved to Downloads are not touched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        Storage.clearRecents(MainActivity.this);
                        refreshRecents();
                    }
                }).show();
    }

    private void refreshRecents() {
        if (filesList == null || homeRecent == null) return;
        final List<Storage.Recent> list = Storage.recents(this);

        /* --- full list --- */
        filesList.removeAllViews();
        if (list.isEmpty()) {
            filesList.addView(emptyFiles());
        } else {
            for (Storage.Recent r : list) {
                filesList.addView(recentRow(r), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 10));
            }
        }

        /* --- mini list on home (latest 3) --- */
        homeRecent.removeAllViews();
        if (!list.isEmpty()) {
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.sectionTitle(this, "Recent"), new LinearLayout.LayoutParams(0, Ui.WRAP, 1f));
            TextView all = Ui.text(this, "See all", 13.5f, Theme.dark ? 0xFFB69CFF : Theme.PURPLE, true);
            all.setPadding(Ui.dp(10), Ui.dp(6), Ui.dp(10), Ui.dp(6));
            all.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(TAB_FILES);
                }
            });
            head.addView(all);
            homeRecent.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 14, 0, 8));
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                homeRecent.addView(recentRow(list.get(i)), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 8));
            }
        }
    }

    private View emptyFiles() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(24), Ui.dp(60), Ui.dp(24), Ui.dp(24));
        FrameLayout tile = Ui.iconTile(this, R.drawable.ic_folder, 0xFF0EA5E9, 0xFF6366F1, 72, 24);
        box.addView(tile);
        TextView t = Ui.text(this, "No files yet", 18, Theme.text, true);
        box.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 18, 0, 0));
        TextView s = Ui.text(this, "Everything you create shows up here, ready to open, share or save to Downloads.", 14, Theme.muted, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(0, 1.15f);
        box.addView(s, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 8, 0, 20));
        TextView go = Ui.primaryButton(this, "Browse tools");
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                select(TAB_HOME);
            }
        });
        box.addView(go, new LinearLayout.LayoutParams(Ui.dp(200), Ui.WRAP));
        return box;
    }

    private View recentRow(final Storage.Recent r) {
        final File file = new File(r.path);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(Ui.stroked(Theme.surface, Theme.stroke, 18, 1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        row.setBackground(Ui.ripple(Ui.shape(Color.TRANSPARENT, 18), 18));
        row.setClickable(true);

        int icon = R.drawable.ic_file_pdf;
        int c1 = 0xFFEF4444;
        int c2 = 0xFFF97316;
        if (r.mime != null && r.mime.startsWith("image/")) {
            icon = R.drawable.ic_image;
            c1 = 0xFFA855F7;
            c2 = 0xFF6366F1;
        } else if (r.mime != null && r.mime.startsWith("text/")) {
            icon = R.drawable.ic_pdf_to_text;
            c1 = 0xFF14B8A6;
            c2 = 0xFF22C55E;
        }
        row.addView(Ui.iconTile(this, icon, c1, c2, 44, 13));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.ellipsized(Ui.text(this, r.name, 15, Theme.text, true), 1));
        String sub = (r.tool == null || r.tool.isEmpty() ? "" : r.tool + " · ") + Storage.human(file.exists() ? file.length() : r.size) + " · " + Storage.ago(r.time);
        col.addView(Ui.ellipsized(Ui.text(this, sub, 12.5f, Theme.muted, false), 1), Ui.lp(Ui.WRAP, Ui.WRAP, 0, 3, 0, 0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        cl.setMargins(Ui.dp(12), 0, Ui.dp(8), 0);
        row.addView(col, cl);
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_down);
        chev.setColorFilter(Theme.muted);
        row.addView(chev, new LinearLayout.LayoutParams(Ui.dp(20), Ui.dp(20)));
        wrap.addView(row);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(Ui.dp(10), 0, Ui.dp(10), Ui.dp(12));
        actions.setVisibility(View.GONE);
        actions.addView(actionButton("Open", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!file.exists()) {
                    gone();
                    return;
                }
                Storage.open(MainActivity.this, file, r.mime);
            }
        }), Ui.weight(1, 3, 0, 3, 0));
        actions.addView(actionButton("Share", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!file.exists()) {
                    gone();
                    return;
                }
                List<File> fs = new ArrayList<>();
                List<String> ms = new ArrayList<>();
                fs.add(file);
                ms.add(r.mime);
                Storage.share(MainActivity.this, fs, ms);
            }
        }), Ui.weight(1, 3, 0, 3, 0));
        actions.addView(actionButton("Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCopy(r, file);
            }
        }), Ui.weight(1, 3, 0, 3, 0));
        actions.addView(actionButton("Delete", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Storage.removeRecent(MainActivity.this, r);
                refreshRecents();
            }
        }), Ui.weight(1, 3, 0, 3, 0));
        wrap.addView(actions);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.setVisibility(actions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        return wrap;
    }

    private TextView actionButton(String label, View.OnClickListener l) {
        TextView t = Ui.softButton(this, label);
        t.setPadding(Ui.dp(6), Ui.dp(10), Ui.dp(6), Ui.dp(10));
        t.setOnClickListener(l);
        return t;
    }

    private void gone() {
        Toast.makeText(this, "That file is no longer available", Toast.LENGTH_SHORT).show();
        refreshRecents();
    }

    private void saveCopy(final Storage.Recent r, final File file) {
        if (!file.exists()) {
            gone();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    Storage.saveToDownloads(MainActivity.this, file, r.name, r.mime);
                    msg = "Saved to Downloads/" + Storage.SUBDIR;
                } catch (Exception e) {
                    msg = "Could not save: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /* ===================================================================== *
     *  SETTINGS
     * ===================================================================== */
    private View buildSettings() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout hdr = Ui.header(this, "Settings", "Appearance and about", 0xFF7C3AED, 0xFF4F46E5, null);
        hdr.getChildAt(0).setVisibility(View.GONE);
        page.addView(hdr);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(28));
        sv.addView(col, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        page.addView(sv, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        /* appearance */
        LinearLayout look = Ui.card(this);
        look.addView(Ui.text(this, "Appearance", 16, Theme.text, true));
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"System", "Light", "Dark"};
        int cur = Theme.mode(this);
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView chip = Ui.chip(this, names[i], cur == i);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (Theme.mode(MainActivity.this) != m) {
                        Theme.setMode(MainActivity.this, m);
                        lastTab = TAB_SETTINGS;
                        recreate();
                    }
                }
            });
            modes.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
        }
        look.addView(modes, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 12, 0, 0));
        col.addView(look, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 12));

        /* privacy */
        LinearLayout priv = Ui.card(this);
        priv.addView(Ui.text(this, "Private by design", 16, Theme.text, true));
        priv.addView(point(R.drawable.ic_shield, "Your files never leave this phone. WebyPDF does not even have internet permission."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 12, 0, 0));
        priv.addView(point(R.drawable.ic_offline, "Everything works offline — on a plane, in a tunnel, anywhere."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        priv.addView(point(R.drawable.ic_download, "Results are kept in the Files tab. Use Save to copy them to Downloads/WebyPDF."), Ui.lp(Ui.MATCH, Ui.WRAP, 0, 10, 0, 0));
        col.addView(priv, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 12));

        /* about */
        LinearLayout about = Ui.card(this);
        about.addView(Ui.text(this, "About WebyPDF", 16, Theme.text, true));
        String ver = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName == null ? "" : pi.versionName;
        } catch (Exception ignored) {
            // version stays empty
        }
        TextView body = Ui.text(this, "Version " + ver + "\n" + Tools.ALL.size() + " tools: merge, split, compress, sign, protect, convert and more.\nPDF engine: Apache PDFBox (Android port) and Android PdfRenderer.", 13.5f, Theme.muted, false);
        body.setLineSpacing(0, 1.2f);
        about.addView(body, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 10, 0, 0));
        col.addView(about, Ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, 12));
        return page;
    }

    private View point(int icon, String text) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(Ui.iconTile(this, icon, Theme.PURPLE, Theme.PINK, 32, 10));
        TextView t = Ui.text(this, text, 13.5f, Theme.text, false);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        lp.setMargins(Ui.dp(12), 0, 0, 0);
        r.addView(t, lp);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    // keeps the compiler honest about unused imports when code is trimmed
    @SuppressWarnings("unused")
    private static void unused(Activity a, ViewGroup g) {
    }
}
