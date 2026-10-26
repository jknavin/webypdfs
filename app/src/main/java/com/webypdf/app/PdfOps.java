package com.webypdf.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.media.ExifInterface;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The actual PDF work. Every public static method here is one tool. */
final class PdfOps {
    static final String PDF = "application/pdf";
    private static final float MM = 72f / 25.4f;

    private PdfOps() {
    }

    /* =========================================================================
     *  helpers
     * ========================================================================= */
    static PDDocument open(Tool.InFile f) throws Exception {
        try {
            return PDDocument.load(f.file);
        } catch (IOException e) {
            throw friendly(e, f.name);
        }
    }

    static Exception friendly(IOException e, String name) {
        String cls = e.getClass().getSimpleName();
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (cls.contains("Password") || m.contains("password")) {
            return new Tool.UserError(name + " is password-protected. Use the “Unlock PDF” tool first.");
        }
        return new Tool.UserError(name + " could not be read. It may be damaged or not a valid PDF.");
    }

    static void closeQuiet(PDDocument d) {
        if (d != null) {
            try {
                d.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    static void saveDoc(PDDocument d, File out) throws IOException {
        FileOutputStream os = new FileOutputStream(out);
        try {
            d.save(os);
        } finally {
            os.close();
        }
    }

    static Tool.Result oneFile(Tool.Ctx c, PDDocument d, String name, String note, boolean brand) throws IOException {
        if (brand) {
            d.getDocumentInformation().setProducer("WebyPDF");
        }
        File out = Storage.newOut(c.app, name);
        saveDoc(d, out);
        Tool.Result r = new Tool.Result();
        r.note = note;
        r.outs.add(new Tool.OutFile(out, name, PDF));
        return r;
    }

    static Tool.Result oneFile(Tool.Ctx c, PDDocument d, String name, String note) throws IOException {
        return oneFile(c, d, name, note, true);
    }

    static byte[] jpeg(Bitmap b, int quality) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, bo);
        return bo.toByteArray();
    }

    static void copyFile(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /** "1-3, 5, 8-" / "last" -> one int[] of 0-based indices per comma separated part. */
    static List<int[]> parseRanges(String s, int total) throws Tool.UserError {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace("last", String.valueOf(total)).replace('–', '-');
        if (t.isEmpty()) {
            throw new Tool.UserError("Enter at least one page or range, for example 1-3, 5");
        }
        Pattern pat = Pattern.compile("^(\\d*)(-(\\d*))?$");
        List<int[]> out = new ArrayList<>();
        for (String part : t.split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            Matcher m = pat.matcher(part);
            if (!m.matches() || (m.group(1).isEmpty() && (m.group(2) == null || m.group(3).isEmpty()))) {
                throw new Tool.UserError("“" + part + "” is not a valid page or range");
            }
            int a = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
            int b = m.group(2) == null ? a : (m.group(3).isEmpty() ? total : Integer.parseInt(m.group(3)));
            if (a < 1 || b < 1 || a > total || b > total) {
                throw new Tool.UserError("“" + part + "” is outside this document (pages 1–" + total + ")");
            }
            int len = Math.abs(b - a) + 1;
            int[] idx = new int[len];
            for (int i = 0; i < len; i++) {
                idx[i] = (a <= b ? a + i : a - i) - 1;
            }
            out.add(idx);
        }
        return out;
    }

    static int[] flat(List<int[]> parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] r = new int[n];
        int k = 0;
        for (int[] p : parts) {
            for (int v : p) r[k++] = v;
        }
        return r;
    }

    /** Page geometry: converts "as displayed" coordinates into the page's own coordinates. */
    static final class Geom {
        final float W, H;       // displayed width / height
        final int R;            // page /Rotate
        private final float bx, by, w0, h0;

        Geom(PDPage p) {
            PDRectangle b = p.getCropBox();
            w0 = b.getWidth();
            h0 = b.getHeight();
            bx = b.getLowerLeftX();
            by = b.getLowerLeftY();
            R = p.getRotation();
            boolean swap = R % 180 != 0;
            W = swap ? h0 : w0;
            H = swap ? w0 : h0;
        }

        /** (vx, vy) measured from the bottom-left of the displayed page. */
        float[] place(float vx, float vy) {
            float x, y;
            switch (R) {
                case 90:
                    x = w0 - vy;
                    y = vx;
                    break;
                case 180:
                    x = w0 - vx;
                    y = h0 - vy;
                    break;
                case 270:
                    x = vy;
                    y = h0 - vx;
                    break;
                default:
                    x = vx;
                    y = vy;
                    break;
            }
            return new float[]{x + bx, y + by};
        }
    }

    private static String nz(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /* =========================================================================
     *  ORGANIZE
     * ========================================================================= */
    static Tool.Result merge(Tool.Ctx c) throws Exception {
        PDDocument out = new PDDocument();
        List<PDDocument> sources = new ArrayList<>();
        try {
            int total = 0;
            for (int i = 0; i < c.files.size(); i++) {
                Tool.InFile f = c.files.get(i);
                c.report(i / (float) c.files.size(), "Adding " + f.name);
                PDDocument d = open(f);
                sources.add(d); // sources must stay open until the merged file is saved
                for (PDPage p : d.getPages()) {
                    out.importPage(p);
                    total++;
                }
            }
            c.report(0.93f, "Saving…");
            return oneFile(c, out, "merged.pdf", c.files.size() + " files combined · " + total + " pages");
        } finally {
            closeQuiet(out);
            for (PDDocument d : sources) closeQuiet(d);
        }
    }

    static Tool.Result split(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument src = open(f);
        try {
            int total = src.getNumberOfPages();
            String mode = c.opt("mode");
            List<int[]> groups = new ArrayList<>();
            if ("each".equals(mode)) {
                for (int i = 0; i < total; i++) groups.add(new int[]{i});
            } else if ("every".equals(mode)) {
                int n = Math.max(1, c.optInt("n", 5));
                for (int i = 0; i < total; i += n) {
                    int len = Math.min(n, total - i);
                    int[] g = new int[len];
                    for (int k = 0; k < len; k++) g[k] = i + k;
                    groups.add(g);
                }
            } else {
                groups = parseRanges(c.opt("ranges"), total);
            }
            Tool.Result r = new Tool.Result();
            String base = Storage.baseName(f.name);
            for (int gi = 0; gi < groups.size(); gi++) {
                c.report(gi / (float) groups.size(), "Creating file " + (gi + 1) + " of " + groups.size());
                int[] g = groups.get(gi);
                PDDocument out = new PDDocument();
                try {
                    for (int idx : g) out.importPage(src.getPage(idx));
                    out.getDocumentInformation().setProducer("WebyPDF");
                    String label = g.length == 1 ? "page-" + (g[0] + 1) : "pages-" + (g[0] + 1) + "-" + (g[g.length - 1] + 1);
                    String name = base + "_" + label + ".pdf";
                    File of = Storage.newOut(c.app, name);
                    saveDoc(out, of);
                    r.outs.add(new Tool.OutFile(of, name, PDF));
                } finally {
                    closeQuiet(out);
                }
            }
            r.note = total + " pages split into " + r.outs.size() + " file" + (r.outs.size() == 1 ? "" : "s");
            return r;
        } finally {
            closeQuiet(src);
        }
    }

    static Tool.Result extract(Tool.Ctx c) throws Exception {
        int[] sel = c.selection.clone();
        Arrays.sort(sel);
        if (sel.length == 0) throw new Tool.UserError("Select at least one page to extract");
        Tool.InFile f = c.files.get(0);
        PDDocument src = open(f);
        PDDocument out = new PDDocument();
        try {
            for (int i : sel) out.importPage(src.getPage(i));
            return oneFile(c, out, Storage.baseName(f.name) + "_extracted.pdf",
                    sel.length + " of " + src.getNumberOfPages() + " pages extracted");
        } finally {
            closeQuiet(out);
            closeQuiet(src);
        }
    }

    static Tool.Result remove(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument src = open(f);
        PDDocument out = new PDDocument();
        try {
            int total = src.getNumberOfPages();
            boolean[] drop = new boolean[total];
            int dropped = 0;
            for (int i : c.selection) {
                if (i >= 0 && i < total && !drop[i]) {
                    drop[i] = true;
                    dropped++;
                }
            }
            if (dropped == 0) throw new Tool.UserError("Select at least one page to remove");
            if (dropped == total) throw new Tool.UserError("You cannot remove every page");
            for (int i = 0; i < total; i++) {
                if (!drop[i]) out.importPage(src.getPage(i));
            }
            return oneFile(c, out, Storage.baseName(f.name) + "_trimmed.pdf",
                    dropped + " page" + (dropped == 1 ? "" : "s") + " removed · " + (total - dropped) + " of " + total + " remain");
        } finally {
            closeQuiet(out);
            closeQuiet(src);
        }
    }

    static Tool.Result organize(Tool.Ctx c) throws Exception {
        if (c.orgSrc.length == 0) throw new Tool.UserError("Your document has no pages left");
        Tool.InFile f = c.files.get(0);
        PDDocument src = open(f);
        PDDocument out = new PDDocument();
        try {
            PDRectangle first = src.getPage(0).getMediaBox();
            for (int i = 0; i < c.orgSrc.length; i++) {
                if (i % 10 == 0) c.report(i / (float) c.orgSrc.length, "Building page " + (i + 1));
                int s = c.orgSrc[i];
                if (s < 0) {
                    out.addPage(new PDPage(new PDRectangle(first.getWidth(), first.getHeight())));
                    continue;
                }
                PDPage p = out.importPage(src.getPage(s));
                int rot = c.orgRot[i];
                if (rot != 0) p.setRotation((p.getRotation() + rot) % 360);
            }
            return oneFile(c, out, Storage.baseName(f.name) + "_organized.pdf", c.orgSrc.length + " pages in your new order");
        } finally {
            closeQuiet(out);
            closeQuiet(src);
        }
    }

    static Tool.Result rotate(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            int total = d.getNumberOfPages();
            String which = c.opt("which");
            boolean[] mark = new boolean[total];
            if ("range".equals(which)) {
                for (int i : flat(parseRanges(c.opt("range"), total))) mark[i] = true;
            } else {
                for (int i = 0; i < total; i++) {
                    mark[i] = "all".equals(which) || ("odd".equals(which) && i % 2 == 0) || ("even".equals(which) && i % 2 == 1);
                }
            }
            int angle = c.optInt("angle", 90);
            int n = 0;
            for (int i = 0; i < total; i++) {
                if (!mark[i]) continue;
                PDPage p = d.getPage(i);
                p.setRotation((p.getRotation() + angle) % 360);
                n++;
            }
            return oneFile(c, d, Storage.baseName(f.name) + "_rotated.pdf", n + " of " + total + " pages rotated");
        } finally {
            closeQuiet(d);
        }
    }

    static Tool.Result crop(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            float t = c.optFloat("top", 10) * MM, b = c.optFloat("bottom", 10) * MM;
            float l = c.optFloat("left", 10) * MM, r = c.optFloat("right", 10) * MM;
            for (PDPage p : d.getPages()) {
                PDRectangle box = p.getCropBox();
                int rot = p.getRotation();
                // visual margins -> margins in the page's own (unrotated) space
                float ut, ur, ub, ul;
                if (rot == 90) {
                    ut = r; ur = b; ub = l; ul = t;
                } else if (rot == 180) {
                    ut = b; ur = l; ub = t; ul = r;
                } else if (rot == 270) {
                    ut = l; ur = t; ub = r; ul = b;
                } else {
                    ut = t; ur = r; ub = b; ul = l;
                }
                float w = box.getWidth() - ul - ur;
                float h = box.getHeight() - ut - ub;
                if (w < 20 || h < 20) {
                    throw new Tool.UserError("Those margins are larger than the page. Use smaller values.");
                }
                PDRectangle nb = new PDRectangle(box.getLowerLeftX() + ul, box.getLowerLeftY() + ub, w, h);
                p.setMediaBox(nb);
                p.setCropBox(nb);
            }
            return oneFile(c, d, Storage.baseName(f.name) + "_cropped.pdf", d.getNumberOfPages() + " pages cropped");
        } finally {
            closeQuiet(d);
        }
    }

    /* =========================================================================
     *  OPTIMIZE
     * ========================================================================= */
    private static Tool.Result rasterize(Tool.Ctx c, String suffix, float dpi, int quality, boolean gray) throws Exception {
        Tool.InFile f = c.files.get(0);
        PdfPages pp;
        try {
            pp = new PdfPages(f.file);
        } catch (IOException e) {
            throw new Tool.UserError(f.name + " could not be read. It may be damaged.");
        }
        PDDocument out = new PDDocument();
        try {
            for (int i = 0; i < pp.count; i++) {
                c.report(i / (float) pp.count, "Page " + (i + 1) + " of " + pp.count);
                float[] sz = pp.size(i);
                Bitmap bmp = pp.render(i, dpi / 72f, gray);
                byte[] data = jpeg(bmp, quality);
                bmp.recycle();
                PDImageXObject img = JPEGFactory.createFromStream(out, new ByteArrayInputStream(data));
                PDPage page = new PDPage(new PDRectangle(sz[0], sz[1]));
                out.addPage(page);
                PDPageContentStream cs = new PDPageContentStream(out, page);
                try {
                    cs.drawImage(img, 0, 0, sz[0], sz[1]);
                } finally {
                    cs.close();
                }
            }
            c.report(0.97f, "Saving…");
            return oneFile(c, out, Storage.baseName(f.name) + suffix, "");
        } finally {
            pp.close();
            closeQuiet(out);
        }
    }

    static Tool.Result compress(Tool.Ctx c) throws Exception {
        String level = c.opt("level");
        float dpi = "high".equals(level) ? 150f : "max".equals(level) ? 76f : 110f;
        int q = "high".equals(level) ? 80 : "max".equals(level) ? 50 : 65;
        Tool.InFile f = c.files.get(0);
        Tool.Result r = rasterize(c, "_compressed.pdf", dpi, q, false);
        Tool.OutFile o = r.outs.get(0);
        long after = o.file.length();
        if (after >= f.size) {
            copyFile(f.file, o.file); // never return a bigger file than the original
            r.note = "This PDF is already well optimised — a smaller version was not possible, so the original was kept.";
        } else {
            r.note = Storage.human(f.size) + " → " + Storage.human(after) + " · saved " + Math.round((1 - after / (double) f.size) * 100) + "%";
        }
        return r;
    }

    static Tool.Result grayscale(Tool.Ctx c) throws Exception {
        Tool.Result r = rasterize(c, "_grayscale.pdf", c.optInt("dpi", 130), 78, true);
        r.note = "Result " + Storage.human(r.outs.get(0).file.length()) + " · pages are flattened to images";
        return r;
    }

    /* =========================================================================
     *  CONVERT
     * ========================================================================= */
    static Bitmap decodeImage(Tool.InFile f, int maxSide) throws Tool.UserError {
        String path = f.file.getAbsolutePath();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            throw new Tool.UserError(f.name + " is not a supported image.");
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(path, o2);
        if (b == null) throw new Tool.UserError("Could not open " + f.name);
        int deg = exifDegrees(path);
        if (deg != 0) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(deg);
            Bitmap rb = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (rb != b) b.recycle();
            b = rb;
        }
        int big = Math.max(b.getWidth(), b.getHeight());
        if (big > maxSide) {
            float k = maxSide / (float) big;
            Bitmap sb = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            if (sb != b) b.recycle();
            b = sb;
        }
        return b;
    }

    private static int exifDegrees(String path) {
        try {
            ExifInterface ex = new ExifInterface(path);
            int o = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
            // no EXIF data
        }
        return 0;
    }

    static float[] pageSize(String key) {
        if ("letter".equals(key)) return new float[]{612f, 792f};
        if ("legal".equals(key)) return new float[]{612f, 1008f};
        if ("a5".equals(key)) return new float[]{419.53f, 595.28f};
        return new float[]{595.28f, 841.89f};
    }

    static Tool.Result imagesToPdf(Tool.Ctx c) throws Exception {
        PDDocument out = new PDDocument();
        try {
            String size = c.opt("size");
            int quality = c.optInt("quality", 90);
            float margin = c.optInt("margin", 18);
            for (int i = 0; i < c.files.size(); i++) {
                Tool.InFile f = c.files.get(i);
                c.report(i / (float) c.files.size(), "Image " + (i + 1) + " of " + c.files.size());
                Bitmap bmp = decodeImage(f, 3200);
                int iw = bmp.getWidth(), ih = bmp.getHeight();
                PDImageXObject img;
                if (bmp.hasAlpha()) {
                    img = LosslessFactory.createFromImage(out, bmp);
                } else {
                    img = JPEGFactory.createFromStream(out, new ByteArrayInputStream(jpeg(bmp, quality)));
                }
                bmp.recycle();
                float pw, ph, m = 0;
                if ("fit".equals(size)) {
                    float s = Math.min(0.75f, 1600f / Math.max(iw, ih));
                    pw = iw * s;
                    ph = ih * s;
                } else {
                    float[] ps = pageSize(size);
                    pw = ps[0];
                    ph = ps[1];
                    String orient = c.opt("orient");
                    boolean land = "landscape".equals(orient) || ("auto".equals(orient) && iw > ih);
                    if (land) {
                        float t = pw;
                        pw = ph;
                        ph = t;
                    }
                    m = margin;
                }
                PDPage page = new PDPage(new PDRectangle(pw, ph));
                out.addPage(page);
                float k = Math.min((pw - 2 * m) / iw, (ph - 2 * m) / ih);
                float dw = iw * k, dh = ih * k;
                PDPageContentStream cs = new PDPageContentStream(out, page);
                try {
                    cs.drawImage(img, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh);
                } finally {
                    cs.close();
                }
            }
            c.report(0.97f, "Saving…");
            int n = c.files.size();
            return oneFile(c, out, "images.pdf", n + " image" + (n == 1 ? "" : "s") + " → " + n + " page" + (n == 1 ? "" : "s"));
        } finally {
            closeQuiet(out);
        }
    }

    static Tool.Result pdfToImages(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PdfPages pp;
        try {
            pp = new PdfPages(f.file);
        } catch (IOException e) {
            throw new Tool.UserError(f.name + " could not be read. It may be damaged.");
        }
        try {
            int total = pp.count;
            int[] list;
            String pages = c.opt("pages").trim();
            if (pages.isEmpty()) {
                list = new int[total];
                for (int i = 0; i < total; i++) list[i] = i;
            } else {
                int[] fl = flat(parseRanges(pages, total));
                boolean[] seen = new boolean[total];
                for (int i : fl) seen[i] = true;
                int n = 0;
                for (boolean b : seen) if (b) n++;
                list = new int[n];
                int k = 0;
                for (int i = 0; i < total; i++) if (seen[i]) list[k++] = i;
            }
            boolean png = "png".equals(c.opt("fmt"));
            float scale = c.optInt("dpi", 150) / 72f;
            Tool.Result r = new Tool.Result();
            String base = Storage.baseName(f.name);
            int digits = String.valueOf(total).length() > 2 ? 3 : 2;
            for (int k = 0; k < list.length; k++) {
                int i = list[k];
                c.report(k / (float) list.length, "Page " + (i + 1) + " (" + (k + 1) + " of " + list.length + ")");
                Bitmap bmp = pp.render(i, scale, false);
                String name = base + "_page-" + String.format(Locale.US, "%0" + digits + "d", i + 1) + (png ? ".png" : ".jpg");
                File of = Storage.newOut(c.app, name);
                FileOutputStream os = new FileOutputStream(of);
                try {
                    bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, os);
                } finally {
                    os.close();
                }
                bmp.recycle();
                r.outs.add(new Tool.OutFile(of, name, png ? "image/png" : "image/jpeg"));
            }
            r.note = r.outs.size() + " image" + (r.outs.size() == 1 ? "" : "s") + " created";
            return r;
        } finally {
            pp.close();
        }
    }

    static Tool.Result pdfToText(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            int n = d.getNumberOfPages();
            boolean marks = c.optBool("marks");
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                c.report((i - 1) / (float) n, "Reading page " + i + " of " + n);
                st.setStartPage(i);
                st.setEndPage(i);
                String t = st.getText(d);
                if (i > 1) {
                    sb.append(marks ? "\n\n— Page " + i + " —\n\n" : "\n\n");
                }
                sb.append(t == null ? "" : t.trim());
            }
            String text = sb.toString().trim();
            String name = Storage.baseName(f.name) + ".txt";
            File of = Storage.newOut(c.app, name);
            Writer w = new OutputStreamWriter(new FileOutputStream(of), StandardCharsets.UTF_8);
            try {
                w.write(text);
            } finally {
                w.close();
            }
            Tool.Result r = new Tool.Result();
            r.outs.add(new Tool.OutFile(of, name, "text/plain"));
            r.text = text;
            r.note = text.length() < 5
                    ? "No selectable text found. This looks like a scanned PDF (OCR is not available)."
                    : text.split("\\s+").length + " words extracted";
            return r;
        } finally {
            closeQuiet(d);
        }
    }

    static Tool.Result textToPdf(Tool.Ctx c) throws Exception {
        String text = c.text == null ? "" : c.text.replace("\r", "").replace("\t", "    ");
        if (text.trim().isEmpty()) throw new Tool.UserError("Type or paste some text first");
        float[] ps = pageSize(c.opt("page"));
        int pw = Math.round(ps[0]), ph = Math.round(ps[1]);
        int margin = c.optInt("margin", 56);
        float size = Math.max(7, Math.min(40, c.optInt("size", 12)));
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextSize(size);
        tp.setColor(0xFF111827);
        String font = c.opt("font");
        tp.setTypeface("serif".equals(font) ? Typeface.SERIF : "mono".equals(font) ? Typeface.MONOSPACE : Typeface.SANS_SERIF);
        int width = pw - 2 * margin;
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), tp, width)
                .setLineSpacing(0f, 1.3f).setIncludePad(false).build();
        int avail = ph - 2 * margin;
        PdfDocument pd = new PdfDocument();
        File of;
        String name = "text.pdf";
        int pages = 0;
        try {
            int line = 0;
            int lines = layout.getLineCount();
            while (line < lines) {
                int top = layout.getLineTop(line);
                int end = line;
                while (end < lines && layout.getLineBottom(end) - top <= avail) end++;
                if (end == line) end = line + 1;
                c.report(line / (float) lines, "Page " + (pages + 1));
                PdfDocument.Page page = pd.startPage(new PdfDocument.PageInfo.Builder(pw, ph, pages + 1).create());
                Canvas cv = page.getCanvas();
                cv.save();
                cv.translate(margin, margin - top);
                cv.clipRect(0, top, width, layout.getLineBottom(end - 1));
                layout.draw(cv);
                cv.restore();
                pd.finishPage(page);
                pages++;
                line = end;
            }
            of = Storage.newOut(c.app, name);
            OutputStream os = new FileOutputStream(of);
            try {
                pd.writeTo(os);
            } finally {
                os.close();
            }
        } finally {
            pd.close();
        }
        Tool.Result r = new Tool.Result();
        r.outs.add(new Tool.OutFile(of, name, PDF));
        r.note = pages + " page" + (pages == 1 ? "" : "s") + " created";
        return r;
    }

    /* =========================================================================
     *  EDIT
     * ========================================================================= */
    static Tool.Result pageNumbers(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            int total = d.getNumberOfPages();
            float size = c.optInt("size", 11);
            int from = Math.max(1, c.optInt("from", 1));
            int start = c.optInt("start", 1);
            String pos = c.opt("pos");
            String fmt = c.opt("fmt");
            int col = Color.parseColor(c.opt("color"));
            int cr = Color.red(col), cg = Color.green(col), cb = Color.blue(col);
            PDType1Font font = PDType1Font.HELVETICA;
            float mg = 28f;
            int done = 0;
            for (int i = from - 1; i < total; i++) {
                PDPage p = d.getPage(i);
                int n = start + (i - (from - 1));
                String label = fmt.replace("{n}", String.valueOf(n)).replace("{total}", String.valueOf(start + total - from));
                float tw = font.getStringWidth(label) / 1000f * size;
                Geom g = new Geom(p);
                float vx = pos.charAt(1) == 'c' ? (g.W - tw) / 2f : pos.charAt(1) == 'r' ? g.W - mg - tw : mg;
                float vy = pos.charAt(0) == 'b' ? mg : g.H - mg - size;
                float[] pt = g.place(vx, vy);
                PDPageContentStream cs = new PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true);
                try {
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.setNonStrokingColor(cr, cg, cb);
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(g.R), pt[0], pt[1]));
                    cs.showText(label);
                    cs.endText();
                } finally {
                    cs.close();
                }
                done++;
            }
            return oneFile(c, d, Storage.baseName(f.name) + "_numbered.pdf", "Numbers added to " + done + " pages");
        } finally {
            closeQuiet(d);
        }
    }

    private static Bitmap textBitmap(String label, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(200f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setColor(color);
        float tw = p.measureText(label);
        Paint.FontMetrics fm = p.getFontMetrics();
        int w = (int) Math.ceil(tw) + 40;
        int h = (int) Math.ceil(fm.descent - fm.ascent) + 40;
        Bitmap b = Bitmap.createBitmap(Math.min(w, 8000), h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawText(label, 20, 20 - fm.ascent, p);
        return b;
    }

    /** Draws {@code img} centred on (cx, cy) rotated by {@code angleDeg}, in the page's own coordinates. */
    private static void stamp(PDPageContentStream cs, PDImageXObject img, float cx, float cy, float w, float h, float angleDeg) throws IOException {
        cs.saveGraphicsState();
        cs.transform(Matrix.getRotateInstance(Math.toRadians(angleDeg), cx, cy));
        cs.drawImage(img, -w / 2f, -h / 2f, w, h);
        cs.restoreGraphicsState();
    }

    private static void setAlpha(PDPageContentStream cs, float alpha) throws IOException {
        if (alpha < 0.999f) {
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(alpha);
            gs.setStrokingAlphaConstant(alpha);
            cs.setGraphicsStateParameters(gs);
        }
    }

    static Tool.Result watermark(Tool.Ctx c) throws Exception {
        String label = c.opt("text").trim();
        if (label.isEmpty()) throw new Tool.UserError("Enter the watermark text");
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            Bitmap wm = textBitmap(label, Color.parseColor(c.opt("color")));
            PDImageXObject img = LosslessFactory.createFromImage(d, wm);
            float ratio = wm.getHeight() / (float) wm.getWidth();
            wm.recycle();
            float opacity = c.optInt("opacity", 25) / 100f;
            float angle = c.optInt("angle", 45);
            float sizePct = c.optInt("size", 65) / 100f;
            boolean tile = c.optBool("tile");
            int total = d.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                if (i % 5 == 0) c.report(i / (float) total, "Page " + (i + 1) + " of " + total);
                PDPage p = d.getPage(i);
                Geom g = new Geom(p);
                PDPageContentStream cs = new PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true);
                try {
                    setAlpha(cs, opacity);
                    if (tile) {
                        float w = g.W * Math.min(0.5f, sizePct * 0.6f);
                        float h = w * ratio;
                        float stepX = w * 1.25f, stepY = h * 2.4f;
                        int row = 0;
                        for (float cy = -g.H * 0.2f; cy < g.H * 1.2f; cy += stepY, row++) {
                            for (float cx = -g.W * 0.2f + (row % 2 == 1 ? stepX / 2f : 0f); cx < g.W * 1.2f; cx += stepX) {
                                float[] pt = g.place(cx, cy);
                                stamp(cs, img, pt[0], pt[1], w, h, angle + g.R);
                            }
                        }
                    } else {
                        float w = g.W * sizePct;
                        float[] pt = g.place(g.W / 2f, g.H / 2f);
                        stamp(cs, img, pt[0], pt[1], w, w * ratio, angle + g.R);
                    }
                } finally {
                    cs.close();
                }
            }
            return oneFile(c, d, Storage.baseName(f.name) + "_watermarked.pdf", "Watermark added to " + total + " page" + (total == 1 ? "" : "s"));
        } finally {
            closeQuiet(d);
        }
    }

    static Tool.Result sign(Tool.Ctx c) throws Exception {
        if (c.sigPath == null) throw new Tool.UserError("Create a signature first");
        Bitmap sig = BitmapFactory.decodeFile(c.sigPath);
        if (sig == null) throw new Tool.UserError("The signature image could not be read. Please create it again.");
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            PDImageXObject img = LosslessFactory.createFromImage(d, sig);
            float aspect = sig.getHeight() / (float) sig.getWidth();
            int total = d.getNumberOfPages();
            List<Integer> targets = new ArrayList<>();
            String apply = c.sigApply;
            if ("all".equals(apply)) {
                for (int i = 0; i < total; i++) targets.add(i);
            } else if ("first".equals(apply)) {
                targets.add(0);
            } else if ("last".equals(apply)) {
                targets.add(total - 1);
            } else {
                targets.add(Math.max(0, Math.min(total - 1, c.sigPage)));
            }
            for (int i : targets) {
                PDPage p = d.getPage(i);
                Geom g = new Geom(p);
                float w = g.W * c.sigW;
                float h = w * aspect;
                float[] pt = g.place(c.sigX * g.W, (1f - c.sigY) * g.H);
                PDPageContentStream cs = new PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true);
                try {
                    stamp(cs, img, pt[0], pt[1], w, h, g.R);
                } finally {
                    cs.close();
                }
            }
            sig.recycle();
            return oneFile(c, d, Storage.baseName(f.name) + "_signed.pdf", "Signature added to " + targets.size() + " page" + (targets.size() == 1 ? "" : "s"));
        } finally {
            closeQuiet(d);
        }
    }

    static Tool.Result metadata(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            if (c.metaClear) {
                d.setDocumentInformation(new PDDocumentInformation());
                return oneFile(c, d, Storage.baseName(f.name) + "_properties.pdf", "All properties removed", false);
            }
            PDDocumentInformation in = d.getDocumentInformation();
            java.util.Map<String, String> m = c.meta;
            in.setTitle(nz(m.get("title")));
            in.setAuthor(nz(m.get("author")));
            in.setSubject(nz(m.get("subject")));
            in.setKeywords(nz(m.get("keywords")));
            in.setCreator(nz(m.get("creator")));
            in.setProducer(nz(m.get("producer")));
            return oneFile(c, d, Storage.baseName(f.name) + "_properties.pdf", "Document properties updated", false);
        } finally {
            closeQuiet(d);
        }
    }

    /* =========================================================================
     *  SECURITY
     * ========================================================================= */
    static Tool.Result protect(Tool.Ctx c) throws Exception {
        String pw = c.opt("password");
        if (pw.isEmpty()) throw new Tool.UserError("Enter a password");
        if (!pw.equals(c.opt("confirm"))) throw new Tool.UserError("The two passwords do not match");
        Tool.InFile f = c.files.get(0);
        PDDocument d = open(f);
        try {
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(c.optBool("print"));
            ap.setCanExtractContent(c.optBool("copy"));
            StandardProtectionPolicy policy = new StandardProtectionPolicy(pw, pw, ap);
            policy.setEncryptionKeyLength(128);
            d.protect(policy);
            return oneFile(c, d, Storage.baseName(f.name) + "_protected.pdf", "Password protection added (AES encryption)");
        } finally {
            closeQuiet(d);
        }
    }

    static Tool.Result unlock(Tool.Ctx c) throws Exception {
        Tool.InFile f = c.files.get(0);
        PDDocument d;
        try {
            d = PDDocument.load(f.file, c.opt("password"));
        } catch (IOException e) {
            String cls = e.getClass().getSimpleName();
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (cls.contains("Password") || m.contains("password")) {
                throw new Tool.UserError("That password is not correct.");
            }
            throw new Tool.UserError(f.name + " could not be read. It may be damaged.");
        }
        try {
            boolean was = d.isEncrypted();
            d.setAllSecurityToBeRemoved(true);
            return oneFile(c, d, Storage.baseName(f.name) + "_unlocked.pdf",
                    was ? "Password removed — this copy opens without a password" : "This PDF was not protected; a copy was saved");
        } finally {
            closeQuiet(d);
        }
    }
}
