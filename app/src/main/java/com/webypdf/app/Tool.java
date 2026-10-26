package com.webypdf.app;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Describes one PDF tool: how it looks, which inputs it needs and what it runs. */
public final class Tool {

    public enum Kind { PDF, IMAGES, NONE }

    public enum Extra { NONE, PAGES_SELECT, ORGANIZE, SIGN, TEXT, METADATA }

    public enum OptType { SELECT, TEXT, NUMBER, PASSWORD, SWITCH, SLIDER, COLOR }

    /** Progress callback: fraction 0..1 and a short label. */
    public interface Progress {
        void update(float fraction, String label);
    }

    public interface Runner {
        Result run(Ctx c) throws Exception;
    }

    /** Error with a message that is safe to show to the user as-is. */
    public static final class UserError extends Exception {
        public UserError(String message) {
            super(message);
        }
    }

    /* ----------------------------- options ----------------------------- */
    public static final class Opt {
        public final String key;
        public final String label;
        public final OptType type;
        public String def = "";
        public String hint = "";
        public String showKey;
        public String showVals;
        public String[][] choices = new String[0][];
        public int min = 0;
        public int max = 100;
        public String unit = "";

        private Opt(String key, String label, OptType type) {
            this.key = key;
            this.label = label;
            this.type = type;
        }

        public static Opt select(String key, String label, String def, String... valueLabelPairs) {
            Opt o = new Opt(key, label, OptType.SELECT);
            o.def = def;
            o.choices = pairs(valueLabelPairs);
            return o;
        }

        public static Opt color(String key, String label, String def, String... valueLabelPairs) {
            Opt o = new Opt(key, label, OptType.COLOR);
            o.def = def;
            o.choices = pairs(valueLabelPairs);
            return o;
        }

        public static Opt text(String key, String label, String def) {
            Opt o = new Opt(key, label, OptType.TEXT);
            o.def = def;
            return o;
        }

        public static Opt number(String key, String label, String def) {
            Opt o = new Opt(key, label, OptType.NUMBER);
            o.def = def;
            return o;
        }

        public static Opt password(String key, String label) {
            return new Opt(key, label, OptType.PASSWORD);
        }

        public static Opt toggle(String key, String label, boolean def) {
            Opt o = new Opt(key, label, OptType.SWITCH);
            o.def = def ? "1" : "0";
            return o;
        }

        public static Opt slider(String key, String label, int def, int min, int max, String unit) {
            Opt o = new Opt(key, label, OptType.SLIDER);
            o.def = String.valueOf(def);
            o.min = min;
            o.max = max;
            o.unit = unit;
            return o;
        }

        public Opt hint(String h) {
            this.hint = h;
            return this;
        }

        /** Show this option only when option {@code key} currently equals one of {@code vals} ("a|b|c"). */
        public Opt showIf(String key, String vals) {
            this.showKey = key;
            this.showVals = vals;
            return this;
        }

        private static String[][] pairs(String[] p) {
            String[][] r = new String[p.length / 2][];
            for (int i = 0; i + 1 < p.length; i += 2) {
                r[i / 2] = new String[]{p[i], p[i + 1]};
            }
            return r;
        }
    }

    /* ----------------------------- runtime data ----------------------------- */
    public static final class InFile {
        public File file;
        public String name;
        public long size;
        public int pages = -1;        // -1 = unknown
        public boolean encrypted;
    }

    public static final class OutFile {
        public final File file;
        public final String name;
        public final String mime;
        public String savedUri;       // content:// uri inside Downloads/WebyPDF (set after saving)

        public OutFile(File file, String name, String mime) {
            this.file = file;
            this.name = name;
            this.mime = mime;
        }
    }

    public static final class Result {
        public final List<OutFile> outs = new ArrayList<>();
        public String note = "";
        public String text;           // optional text to show (PDF to Text)
    }

    /** Everything a runner needs. */
    public static final class Ctx {
        public Context app;
        public List<InFile> files = new ArrayList<>();
        public Map<String, String> opts;
        public Progress progress;

        public int[] selection = new int[0];       // page picker result (0-based)
        public int[] orgSrc = new int[0];          // organizer: source page per output page (-1 = blank)
        public int[] orgRot = new int[0];          // organizer: extra rotation per output page
        public String text = "";                   // text to pdf
        public Map<String, String> meta;           // metadata editor
        public boolean metaClear;
        public String sigPath;                     // signature png
        public int sigPage;
        public float sigX = 0.7f, sigY = 0.85f, sigW = 0.3f;
        public String sigApply = "current";

        public String opt(String k) {
            String v = opts == null ? null : opts.get(k);
            return v == null ? "" : v;
        }

        public int optInt(String k, int def) {
            try {
                return Integer.parseInt(opt(k).trim());
            } catch (Exception e) {
                return def;
            }
        }

        public float optFloat(String k, float def) {
            try {
                return Float.parseFloat(opt(k).trim());
            } catch (Exception e) {
                return def;
            }
        }

        public boolean optBool(String k) {
            return "1".equals(opt(k));
        }

        public void report(float f, String label) {
            if (progress != null) {
                progress.update(Math.max(0f, Math.min(1f, f)), label);
            }
        }
    }

    /* ----------------------------- definition ----------------------------- */
    public final String id;
    public final String name;
    public final String desc;
    public final String cat;
    public final int c1;
    public final int c2;
    public final int icon;
    public Kind kind = Kind.PDF;
    public boolean multi;
    public boolean ordered;
    public boolean popular;
    public int minFiles = 1;
    public String cta;
    public Extra extra = Extra.NONE;
    public final List<Opt> opts = new ArrayList<>();
    public Runner runner;

    public Tool(String id, String name, String desc, String cat, int c1, int c2, int icon) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.cat = cat;
        this.c1 = c1;
        this.c2 = c2;
        this.icon = icon;
        this.cta = name;
    }

    public Tool pdfs(int min) {
        kind = Kind.PDF;
        multi = true;
        minFiles = min;
        return this;
    }

    public Tool images() {
        kind = Kind.IMAGES;
        multi = true;
        minFiles = 1;
        return this;
    }

    public Tool noFiles() {
        kind = Kind.NONE;
        minFiles = 0;
        return this;
    }

    public Tool ordered() {
        ordered = true;
        return this;
    }

    public Tool popular() {
        popular = true;
        return this;
    }

    public Tool cta(String s) {
        cta = s;
        return this;
    }

    public Tool extra(Extra e) {
        extra = e;
        return this;
    }

    public Tool opt(Opt o) {
        opts.add(o);
        return this;
    }

    public Tool run(Runner r) {
        runner = r;
        return this;
    }
}
