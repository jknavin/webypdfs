package com.webypdf.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Page grid used by "Extract / Remove pages" (select mode) and "Organize pages" (organize mode).
 * Select mode returns {@code sel} (0-based page numbers). Organize mode returns {@code src} / {@code rot}.
 */
public class PagesActivity extends Activity {

    private static final class Item {
        int src;   // source page index, -1 = blank page
        int rot;   // extra rotation in degrees (0, 90, 180, 270)

        Item(int src, int rot) {
            this.src = src;
            this.rot = rot;
        }
    }

    private boolean organize;
    private boolean removeVerb;
    private int c1;
    private int total;

    private PdfPages pdf;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private LruCache<Integer, Bitmap> cache;

    private final List<Item> items = new ArrayList<>();
    private boolean[] sel;

    private RecyclerView grid;
    private Adapter adapter;
    private TextView done;
    private int cellW;
    private int cellH;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.apply(this);

        String path = getIntent().getStringExtra("path");
        organize = "organize".equals(getIntent().getStringExtra("mode"));
        removeVerb = "remove".equals(getIntent().getStringExtra("verb"));
        c1 = getIntent().getIntExtra("c1", Theme.PURPLE);
        int c2 = getIntent().getIntExtra("c2", Theme.PINK);
        String title = getIntent().getStringExtra("title");

        try {
            pdf = new PdfPages(new File(path));
        } catch (Throwable e) {
            Toast.makeText(this, "Could not open this PDF", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        total = pdf.count;

        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<Integer, Bitmap>(Math.max(4096, maxKb / 6)) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        // initial state
        int[] src = getIntent().getIntArrayExtra("src");
        int[] rot = getIntent().getIntArrayExtra("rot");
        if (organize && src != null && rot != null && src.length == rot.length) {
            for (int i = 0; i < src.length; i++) items.add(new Item(src[i], rot[i]));
        } else {
            resetItems();
        }
        sel = new boolean[total];
        int[] pre = getIntent().getIntArrayExtra("sel");
        if (pre != null) {
            for (int p : pre) {
                if (p >= 0 && p < total) sel[p] = true;
            }
        }

        Theme.window(this, c1, Theme.nav, !Theme.dark);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Theme.bg);

        String sub = organize ? "Long-press a page and drag to reorder"
                : removeVerb ? "Tap the pages you want to delete" : "Tap the pages you want to keep";
        page.addView(Ui.header(this, title == null ? "Pages" : title, sub, c1, c2, null));
        page.addView(buildToolbar());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cols = Math.max(2, (int) (dm.widthPixels / dm.density / 118f));
        int pad = Ui.dp(8);
        cellW = (dm.widthPixels - pad * 2) / cols;
        cellH = (int) (cellW * 1.38f);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, cols));
        grid.setPadding(pad, pad, pad, pad);
        grid.setClipToPadding(false);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        page.addView(grid, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        if (organize) attachDrag();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Theme.nav);
        bar.addView(Ui.divider(this));
        done = Ui.primaryButton(this, "Done");
        done.setOnClickListener(v -> finishWithResult());
        bar.addView(done, Ui.lp(Ui.MATCH, Ui.WRAP, 16, 12, 16, 12));
        page.addView(bar);

        setContentView(page);
        updateDone();
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        if (pdf != null) pdf.close();
        if (cache != null) cache.evictAll();
        super.onDestroy();
    }

    private void resetItems() {
        items.clear();
        for (int i = 0; i < total; i++) items.add(new Item(i, 0));
    }

    /* ------------------------------ toolbar ------------------------------ */
    private View buildToolbar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(4));
        hs.addView(row);

        if (!organize) {
            addChip(row, "All", () -> setAll(1));
            addChip(row, "None", () -> setAll(0));
            addChip(row, "Invert", () -> setAll(2));
            addChip(row, "Odd", () -> setParity(0));
            addChip(row, "Even", () -> setParity(1));
        } else {
            addChip(row, "Reverse", () -> {
                Collections.reverse(items);
                adapter.notifyDataSetChanged();
            });
            addChip(row, "Rotate all", () -> {
                for (Item it : items) it.rot = (it.rot + 90) % 360;
                adapter.notifyDataSetChanged();
            });
            addChip(row, "Add blank", () -> {
                items.add(new Item(-1, 0));
                adapter.notifyDataSetChanged();
                grid.scrollToPosition(items.size() - 1);
                updateDone();
            });
            addChip(row, "Reset", () -> {
                resetItems();
                adapter.notifyDataSetChanged();
                updateDone();
            });
        }
        return hs;
    }

    private void addChip(LinearLayout row, String label, final Runnable r) {
        TextView c = Ui.chip(this, label, false);
        c.setOnClickListener(v -> r.run());
        row.addView(c, Ui.lp(Ui.WRAP, Ui.WRAP, 0, 0, 8, 0));
    }

    /** mode: 0 = none, 1 = all, 2 = invert */
    private void setAll(int mode) {
        for (int i = 0; i < sel.length; i++) {
            sel[i] = mode == 1 || (mode == 2 && !sel[i]);
        }
        adapter.notifyDataSetChanged();
        updateDone();
    }

    /** parity 0 = odd pages (1, 3, 5…), 1 = even pages */
    private void setParity(int parity) {
        for (int i = 0; i < sel.length; i++) sel[i] = (i % 2) == parity;
        adapter.notifyDataSetChanged();
        updateDone();
    }

    private int selectedCount() {
        int n = 0;
        for (boolean s : sel) {
            if (s) n++;
        }
        return n;
    }

    private void updateDone() {
        if (organize) {
            done.setText("Done · " + items.size() + (items.size() == 1 ? " page" : " pages"));
        } else {
            int n = selectedCount();
            done.setText(n == 0 ? "Select pages" : "Done · " + n + " selected");
            done.setAlpha(n == 0 ? 0.5f : 1f);
        }
    }

    private void finishWithResult() {
        android.content.Intent r = new android.content.Intent();
        if (organize) {
            if (items.isEmpty()) {
                Toast.makeText(this, "Keep at least one page", Toast.LENGTH_SHORT).show();
                return;
            }
            int[] src = new int[items.size()];
            int[] rot = new int[items.size()];
            for (int i = 0; i < src.length; i++) {
                src[i] = items.get(i).src;
                rot[i] = items.get(i).rot;
            }
            r.putExtra("src", src);
            r.putExtra("rot", rot);
        } else {
            int n = selectedCount();
            if (n == 0) {
                Toast.makeText(this, "Select at least one page", Toast.LENGTH_SHORT).show();
                return;
            }
            if (removeVerb && n >= total) {
                Toast.makeText(this, "You can’t remove every page", Toast.LENGTH_SHORT).show();
                return;
            }
            int[] out = new int[n];
            int k = 0;
            for (int i = 0; i < sel.length; i++) {
                if (sel[i]) out[k++] = i;
            }
            r.putExtra("sel", out);
        }
        setResult(RESULT_OK, r);
        finish();
    }

    /* ------------------------------ drag & drop ------------------------------ */
    private void attachDrag() {
        ItemTouchHelper h = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0) {
            @Override
            public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder from, RecyclerView.ViewHolder to) {
                int a = from.getAdapterPosition();
                int b = to.getAdapterPosition();
                if (a < 0 || b < 0) return false;
                Item it = items.remove(a);
                items.add(b, it);
                adapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int dir) {
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder vh, int state) {
                super.onSelectedChanged(vh, state);
                if (vh != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                    vh.itemView.setAlpha(0.8f);
                    vh.itemView.setScaleX(1.05f);
                    vh.itemView.setScaleY(1.05f);
                }
            }

            @Override
            public void clearView(RecyclerView rv, RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setAlpha(1f);
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
                rv.post(() -> adapter.notifyItemRangeChanged(0, items.size()));
            }
        });
        h.attachToRecyclerView(grid);
    }

    /* ------------------------------ adapter ------------------------------ */
    private static Bitmap rotated(Bitmap src, int deg) {
        if (deg % 360 == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private final class VH extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final ImageView img;
        final TextView num;
        final FrameLayout badge;
        final ImageView badgeIcon;
        final LinearLayout actions;
        int boundSrc = -2;
        int boundRot;

        VH(FrameLayout root, FrameLayout card, ImageView img, TextView num, FrameLayout badge, ImageView badgeIcon, LinearLayout actions) {
            super(root);
            this.card = card;
            this.img = img;
            this.num = num;
            this.badge = badge;
            this.badgeIcon = badgeIcon;
            this.actions = actions;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout root = new FrameLayout(PagesActivity.this);
            root.setLayoutParams(new RecyclerView.LayoutParams(cellW, cellH));

            FrameLayout card = new FrameLayout(PagesActivity.this);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH);
            cp.setMargins(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            root.addView(card, cp);

            ImageView img = new ImageView(PagesActivity.this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setBackgroundColor(Color.WHITE);
            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH);
            ip.setMargins(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
            card.addView(img, ip);

            TextView num = Ui.text(PagesActivity.this, "", 11.5f, Color.WHITE, true);
            num.setBackground(Ui.shape(0xB0000000, 10));
            num.setPadding(Ui.dp(8), Ui.dp(2), Ui.dp(8), Ui.dp(2));
            FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.START);
            np.setMargins(Ui.dp(8), Ui.dp(8), 0, 0);
            card.addView(num, np);

            FrameLayout badge = new FrameLayout(PagesActivity.this);
            ImageView bi = new ImageView(PagesActivity.this);
            bi.setColorFilter(Color.WHITE);
            badge.addView(bi, new FrameLayout.LayoutParams(Ui.dp(16), Ui.dp(16), Gravity.CENTER));
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(26), Ui.dp(26), Gravity.TOP | Gravity.END);
            bp.setMargins(0, Ui.dp(8), Ui.dp(8), 0);
            card.addView(badge, bp);

            LinearLayout actions = new LinearLayout(PagesActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER);
            actions.setBackground(Ui.shape(0xB3000000, 14));
            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(Ui.WRAP, Ui.dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            ap.setMargins(0, 0, 0, Ui.dp(8));
            card.addView(actions, ap);

            final VH vh = new VH(root, card, img, num, badge, bi, actions);

            if (organize) {
                actions.addView(actionButton(R.drawable.ic_rotate, () -> {
                    int p = vh.getAdapterPosition();
                    if (p < 0) return;
                    Item it = items.get(p);
                    it.rot = (it.rot + 90) % 360;
                    notifyItemChanged(p);
                }));
                actions.addView(actionButton(R.drawable.ic_dup, () -> {
                    int p = vh.getAdapterPosition();
                    if (p < 0) return;
                    Item it = items.get(p);
                    items.add(p + 1, new Item(it.src, it.rot));
                    notifyItemInserted(p + 1);
                    notifyItemRangeChanged(p, items.size() - p);
                    updateDone();
                }));
                actions.addView(actionButton(R.drawable.ic_remove, () -> {
                    int p = vh.getAdapterPosition();
                    if (p < 0) return;
                    items.remove(p);
                    notifyItemRemoved(p);
                    notifyItemRangeChanged(p, items.size() - p);
                    updateDone();
                }));
                badge.setVisibility(View.GONE);
            } else {
                actions.setVisibility(View.GONE);
                root.setOnClickListener(v -> {
                    int p = vh.getAdapterPosition();
                    if (p < 0) return;
                    int s = items.get(p).src;
                    sel[s] = !sel[s];
                    notifyItemChanged(p);
                    updateDone();
                });
            }
            return vh;
        }

        private View actionButton(int icon, final Runnable r) {
            ImageView iv = new ImageView(PagesActivity.this);
            iv.setImageResource(icon);
            iv.setColorFilter(Color.WHITE);
            iv.setPadding(Ui.dp(7), Ui.dp(7), Ui.dp(7), Ui.dp(7));
            iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(32), Ui.dp(30)));
            iv.setOnClickListener(v -> r.run());
            return iv;
        }

        @Override
        public void onBindViewHolder(final VH vh, int position) {
            final Item it = items.get(position);
            vh.boundSrc = it.src;
            vh.boundRot = it.rot;

            vh.num.setText(String.valueOf(organize ? position + 1 : it.src + 1));

            boolean on = !organize && it.src >= 0 && sel[it.src];
            int accent = removeVerb ? Theme.RED : c1;
            vh.card.setBackground(Ui.stroked(Theme.surface, on ? accent : Theme.stroke, 12, on ? 3 : 1));
            if (!organize) {
                vh.badge.setVisibility(on ? View.VISIBLE : View.GONE);
                vh.badge.setBackground(Ui.shape(accent, 13));
                vh.badgeIcon.setImageResource(removeVerb ? R.drawable.ic_close : R.drawable.ic_check);
            }

            if (it.src < 0) {
                vh.img.setImageBitmap(null);
                return;
            }
            Bitmap bm = cache.get(it.src);
            if (bm != null) {
                vh.img.setImageBitmap(rotated(bm, it.rot));
                return;
            }
            vh.img.setImageBitmap(null);
            final int src = it.src;
            try {
                exec.execute(() -> {
                    if (vh.boundSrc != src) return; // scrolled away
                    Bitmap b = cache.get(src);
                    if (b == null) {
                        try {
                            b = pdf.renderWidth(src, 300);
                        } catch (Throwable e) {
                            return;
                        }
                        cache.put(src, b);
                    }
                    final Bitmap fb = b;
                    runOnUiThread(() -> {
                        if (vh.boundSrc == src) vh.img.setImageBitmap(rotated(fb, vh.boundRot));
                    });
                });
            } catch (Exception ignored) {
                // executor stopped
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
