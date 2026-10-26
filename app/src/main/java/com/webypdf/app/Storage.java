package com.webypdf.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Files in / out: imports picked documents, saves results to Downloads, keeps the "Recent" list. */
public final class Storage {
    public static final String SUBDIR = "WebyPDF";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private Storage() {
    }

    /* ------------------------------ folders ------------------------------ */
    public static File inputsDir(Context c) {
        File d = new File(c.getCacheDir(), "inputs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File outputsDir(Context c) {
        File d = new File(c.getFilesDir(), "outputs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File tmpDir(Context c) {
        File d = new File(c.getCacheDir(), "tmp");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Deletes imported working copies older than a few hours. */
    public static void cleanCache(Context c) {
        long limit = System.currentTimeMillis() - 6L * 3600L * 1000L;
        for (File dir : new File[]{inputsDir(c), tmpDir(c)}) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (f.lastModified() < limit) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    public static String safeName(String raw) {
        String n = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "webypdf-file";
        }
        if (n.length() > 110) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 8 ? n.substring(dot) : "";
            n = n.substring(0, 110 - ext.length()) + ext;
        }
        return n;
    }

    public static String baseName(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** A fresh file in the outputs folder (display name stays the clean one). */
    public static File newOut(Context c, String displayName) {
        return new File(outputsDir(c), System.currentTimeMillis() + "_" + COUNTER.incrementAndGet() + "_" + safeName(displayName));
    }

    /* ------------------------------ import ------------------------------ */
    public static String queryName(Context c, Uri u) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                return cur.getString(0);
            }
        } catch (Exception ignored) {
            // fall through
        } finally {
            if (cur != null) cur.close();
        }
        String p = u.getLastPathSegment();
        return p == null ? null : p.substring(p.lastIndexOf('/') + 1);
    }

    /** Copies a picked document into the app cache and inspects it. */
    public static Tool.InFile importFile(Context c, Uri u, boolean pdf, boolean allowEncrypted) throws Exception {
        String name = queryName(c, u);
        if (name == null || name.isEmpty()) {
            name = pdf ? "document.pdf" : "image";
        }
        File dst = new File(inputsDir(c), System.nanoTime() + "_" + safeName(name));
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) {
            throw new Tool.UserError("Could not open " + name);
        }
        try {
            OutputStream out = new FileOutputStream(dst);
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
        Tool.InFile f = new Tool.InFile();
        f.file = dst;
        f.name = name;
        f.size = dst.length();
        if (pdf) {
            PdfPages pp = null;
            try {
                pp = new PdfPages(dst);
                f.pages = pp.count;
            } catch (SecurityException e) {
                f.encrypted = true;
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                throw new Tool.UserError(name + " is not a valid PDF or is damaged.");
            } finally {
                if (pp != null) pp.close();
            }
            if (f.encrypted && !allowEncrypted) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                throw new Tool.UserError(name + " is password-protected. Use the “Unlock PDF” tool first.");
            }
        }
        return f;
    }

    /* ------------------------------ save to Downloads ------------------------------ */
    /** Saves a copy into Downloads/WebyPDF. Returns the content uri (or null for legacy file saves). */
    public static String saveToDownloads(Context c, File src, String name, String mime) throws IOException {
        String safe = safeName(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            ContentResolver r = c.getContentResolver();
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Could not create file in Downloads");
            OutputStream os = r.openOutputStream(uri);
            if (os == null) throw new IOException("Could not open file in Downloads");
            try {
                copy(src, os);
            } finally {
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            r.update(uri, done, null, null);
            return uri.toString();
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create the WebyPDF folder in Downloads");
        File dst = new File(dir, safe);
        String base = baseName(safe);
        String ext = safe.substring(base.length());
        for (int i = 1; dst.exists() && i < 1000; i++) {
            dst = new File(dir, base + " (" + i + ")" + ext);
        }
        OutputStream os = new FileOutputStream(dst);
        try {
            copy(src, os);
        } finally {
            os.close();
        }
        MediaScannerConnection.scanFile(c, new String[]{dst.getAbsolutePath()}, null, null);
        return null;
    }

    private static void copy(File src, OutputStream os) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
    }

    /* ------------------------------ open / share ------------------------------ */
    public static Uri shareUri(Context c, File f) {
        return FileProvider.getUriForFile(c, c.getPackageName() + ".files", f);
    }

    public static void open(Activity a, File f, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(shareUri(a, f), mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(a, "No app installed that can open this file", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Could not open the file", Toast.LENGTH_LONG).show();
        }
    }

    public static void share(Activity a, List<File> files, List<String> mimes) {
        try {
            Intent i;
            if (files.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(mimes.get(0));
                i.putExtra(Intent.EXTRA_STREAM, shareUri(a, files.get(0)));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                ArrayList<Uri> uris = new ArrayList<>();
                for (File f : files) uris.add(shareUri(a, f));
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            Toast.makeText(a, "Could not share the file", Toast.LENGTH_LONG).show();
        }
    }

    /* ------------------------------ recent files ------------------------------ */
    public static final class Recent {
        public String name;
        public String path;
        public String mime;
        public String tool;
        public String uri;
        public long time;
        public long size;
    }

    private static final int MAX_RECENT = 40;

    public static List<Recent> recents(Context c) {
        List<Recent> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(c.getSharedPreferences("webypdf", Context.MODE_PRIVATE).getString("recents", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Recent r = new Recent();
                r.name = o.optString("name");
                r.path = o.optString("path");
                r.mime = o.optString("mime", "application/pdf");
                r.tool = o.optString("tool");
                r.uri = o.optString("uri", "");
                r.time = o.optLong("time");
                r.size = o.optLong("size");
                list.add(r);
            }
        } catch (Exception ignored) {
            // corrupted list: start fresh
        }
        return list;
    }

    private static void store(Context c, List<Recent> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Recent r : list) {
                JSONObject o = new JSONObject();
                o.put("name", r.name);
                o.put("path", r.path);
                o.put("mime", r.mime);
                o.put("tool", r.tool);
                o.put("uri", r.uri == null ? "" : r.uri);
                o.put("time", r.time);
                o.put("size", r.size);
                arr.put(o);
            }
        } catch (Exception ignored) {
            // ignore
        }
        c.getSharedPreferences("webypdf", Context.MODE_PRIVATE).edit().putString("recents", arr.toString()).apply();
    }

    public static synchronized void addRecent(Context c, Recent r) {
        List<Recent> list = recents(c);
        list.add(0, r);
        while (list.size() > MAX_RECENT) {
            Recent old = list.remove(list.size() - 1);
            //noinspection ResultOfMethodCallIgnored
            new File(old.path).delete();
        }
        store(c, list);
    }

    public static synchronized void removeRecent(Context c, Recent r) {
        List<Recent> list = recents(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).path.equals(r.path)) {
                list.remove(i);
                break;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        new File(r.path).delete();
        store(c, list);
    }

    public static synchronized void clearRecents(Context c) {
        for (Recent r : recents(c)) {
            //noinspection ResultOfMethodCallIgnored
            new File(r.path).delete();
        }
        store(c, new ArrayList<Recent>());
    }

    /* ------------------------------ formatting ------------------------------ */
    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        if (v < 1024) return String.format(Locale.US, v < 10 ? "%.1f KB" : "%.0f KB", v);
        v /= 1024.0;
        if (v < 1024) return String.format(Locale.US, v < 10 ? "%.2f MB" : "%.1f MB", v);
        return String.format(Locale.US, "%.2f GB", v / 1024.0);
    }

    public static String ago(long time) {
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        long d = s / 86400;
        return d == 1 ? "yesterday" : d + " days ago";
    }
}
