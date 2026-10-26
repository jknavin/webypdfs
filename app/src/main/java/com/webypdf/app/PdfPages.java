package com.webypdf.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * Thin, thread-safe wrapper around Android's built-in PdfRenderer (PDFium).
 * Used for thumbnails, previews and for rasterising pages (compress, grayscale, PDF to images).
 */
public final class PdfPages {
    public final int count;
    private final ParcelFileDescriptor fd;
    private final PdfRenderer renderer;

    /** Throws SecurityException for password-protected PDFs and IOException for damaged ones. */
    public PdfPages(File f) throws IOException {
        ParcelFileDescriptor d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer r;
        try {
            r = new PdfRenderer(d);
        } catch (IOException | RuntimeException e) {
            d.close();
            throw e;
        }
        this.fd = d;
        this.renderer = r;
        this.count = r.getPageCount();
    }

    /** Page size in PDF points (1/72 inch), already taking page rotation into account. */
    public synchronized float[] size(int index) {
        PdfRenderer.Page p = renderer.openPage(index);
        try {
            return new float[]{p.getWidth(), p.getHeight()};
        } finally {
            p.close();
        }
    }

    /** Renders a page at {@code scale} pixels per point (1.0 = 72 dpi). */
    public synchronized Bitmap render(int index, float scale, boolean gray) {
        PdfRenderer.Page p = renderer.openPage(index);
        try {
            float s = scale;
            float px = p.getWidth() * s * (float) p.getHeight() * s;
            if (px > 16e6f) {
                s *= (float) Math.sqrt(16e6f / px); // keep bitmaps phone-safe
            }
            int w = Math.max(1, Math.round(p.getWidth() * s));
            int h = Math.max(1, Math.round(p.getHeight() * s));
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return gray ? toGray(b) : b;
        } finally {
            p.close();
        }
    }

    /** Renders a page so that its width is {@code widthPx}. */
    public synchronized Bitmap renderWidth(int index, int widthPx) {
        PdfRenderer.Page p = renderer.openPage(index);
        float pw = p.getWidth();
        p.close();
        return render(index, widthPx / Math.max(1f, pw), false);
    }

    public static Bitmap toGray(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        cv.drawBitmap(src, 0, 0, paint);
        src.recycle();
        return out;
    }

    public synchronized void close() {
        try {
            renderer.close();
        } catch (Exception ignored) {
            // already closed
        }
        try {
            fd.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
