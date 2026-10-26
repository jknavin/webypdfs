package com.webypdf.app;

import java.util.ArrayList;
import java.util.List;

/** Registry of every tool shown in the app. */
public final class Tools {
    public static final List<Tool> ALL = new ArrayList<>();

    /** id, label */
    public static final String[][] CATS = {
            {"all", "All"}, {"organize", "Organize"}, {"convert", "Convert"},
            {"edit", "Edit"}, {"optimize", "Optimize"}, {"security", "Security"}
    };

    private static final String[] COLORS = {
            "#E11D48", "Red", "#2563EB", "Blue", "#16A34A", "Green", "#111827", "Black", "#9333EA", "Purple", "#F59E0B", "Orange"
    };

    static {
        /* ------------------------------- organize ------------------------------- */
        ALL.add(new Tool("merge", "Merge PDF", "Combine several PDFs into one file", "organize", 0xFF8B5CF6, 0xFFEC4899, R.drawable.ic_merge)
                .pdfs(2).ordered().popular().cta("Merge PDFs").run(PdfOps::merge));

        ALL.add(new Tool("split", "Split PDF", "Cut a PDF into separate files", "organize", 0xFF06B6D4, 0xFF3B82F6, R.drawable.ic_split)
                .popular().cta("Split PDF")
                .opt(Tool.Opt.select("mode", "Split by", "ranges", "ranges", "Custom page ranges", "every", "Every N pages", "each", "Every page (one file each)"))
                .opt(Tool.Opt.text("ranges", "Page ranges (each part becomes a file)", "1-3, 4-6").hint("Example: 1-3, 5, 8-").showIf("mode", "ranges"))
                .opt(Tool.Opt.number("n", "Pages per file", "5").showIf("mode", "every"))
                .run(PdfOps::split));

        ALL.add(new Tool("extract", "Extract Pages", "Save only the pages you pick", "organize", 0xFFF59E0B, 0xFFEF4444, R.drawable.ic_extract)
                .extra(Tool.Extra.PAGES_SELECT).cta("Extract pages").run(PdfOps::extract));

        ALL.add(new Tool("remove", "Remove Pages", "Delete unwanted pages", "organize", 0xFFEF4444, 0xFFF97316, R.drawable.ic_remove)
                .extra(Tool.Extra.PAGES_SELECT).cta("Remove pages").run(PdfOps::remove));

        ALL.add(new Tool("organize", "Organize Pages", "Reorder, rotate, duplicate, add blanks", "organize", 0xFF10B981, 0xFF06B6D4, R.drawable.ic_organize)
                .extra(Tool.Extra.ORGANIZE).cta("Save new order").run(PdfOps::organize));

        ALL.add(new Tool("rotate", "Rotate PDF", "Turn all or selected pages", "organize", 0xFF3B82F6, 0xFF8B5CF6, R.drawable.ic_rotate)
                .cta("Rotate pages")
                .opt(Tool.Opt.select("angle", "Rotate", "90", "90", "90° clockwise", "180", "180°", "270", "90° counter-clockwise"))
                .opt(Tool.Opt.select("which", "Pages", "all", "all", "All pages", "odd", "Odd pages", "even", "Even pages", "range", "Custom range"))
                .opt(Tool.Opt.text("range", "Page range", "1-3").hint("Example: 1-3, 7").showIf("which", "range"))
                .run(PdfOps::rotate));

        ALL.add(new Tool("crop", "Crop PDF", "Trim margins from every page", "organize", 0xFFEC4899, 0xFFF59E0B, R.drawable.ic_crop)
                .cta("Crop pages")
                .opt(Tool.Opt.number("top", "Top margin (mm)", "10"))
                .opt(Tool.Opt.number("bottom", "Bottom margin (mm)", "10"))
                .opt(Tool.Opt.number("left", "Left margin (mm)", "10"))
                .opt(Tool.Opt.number("right", "Right margin (mm)", "10"))
                .run(PdfOps::crop));

        /* ------------------------------- optimize ------------------------------- */
        ALL.add(new Tool("compress", "Compress PDF", "Make big PDFs small for sharing", "optimize", 0xFF22C55E, 0xFF06B6D4, R.drawable.ic_compress)
                .popular().cta("Compress PDF")
                .opt(Tool.Opt.select("level", "Compression level", "balanced", "high", "High quality (smaller gain)", "balanced", "Balanced (recommended)", "max", "Maximum (smallest file)")
                        .hint("Pages are turned into optimised images, so text can no longer be selected."))
                .run(PdfOps::compress));

        ALL.add(new Tool("grayscale", "PDF to Grayscale", "Black & white to save ink", "optimize", 0xFF64748B, 0xFF0F172A, R.drawable.ic_grayscale)
                .cta("Convert to grayscale")
                .opt(Tool.Opt.select("dpi", "Quality", "130", "96", "Small file (96 DPI)", "130", "Balanced (130 DPI)", "180", "Sharp (180 DPI)"))
                .run(PdfOps::grayscale));

        /* ------------------------------- convert ------------------------------- */
        ALL.add(new Tool("images-to-pdf", "Images to PDF", "Turn photos into one PDF", "convert", 0xFFF97316, 0xFFEC4899, R.drawable.ic_images_to_pdf)
                .images().ordered().popular().cta("Create PDF")
                .opt(Tool.Opt.select("size", "Page size", "a4", "fit", "Same as image", "a4", "A4", "letter", "US Letter", "a5", "A5"))
                .opt(Tool.Opt.select("orient", "Orientation", "auto", "auto", "Auto (match image)", "portrait", "Portrait", "landscape", "Landscape").showIf("size", "a4|letter|a5"))
                .opt(Tool.Opt.select("margin", "Margin", "18", "0", "None", "18", "Small", "36", "Medium", "56", "Large").showIf("size", "a4|letter|a5"))
                .opt(Tool.Opt.select("quality", "Photo quality", "90", "95", "Best", "90", "High", "75", "Smaller file"))
                .run(PdfOps::imagesToPdf));

        ALL.add(new Tool("pdf-to-images", "PDF to Images", "Save every page as JPG or PNG", "convert", 0xFFA855F7, 0xFF6366F1, R.drawable.ic_pdf_to_images)
                .cta("Convert to images")
                .opt(Tool.Opt.select("fmt", "Format", "jpeg", "jpeg", "JPG (smaller)", "png", "PNG (lossless)"))
                .opt(Tool.Opt.select("dpi", "Resolution", "150", "96", "Screen (96 DPI)", "150", "Good (150 DPI)", "220", "High (220 DPI)", "300", "Print (300 DPI)"))
                .opt(Tool.Opt.text("pages", "Pages (empty = all)", "").hint("Example: 1-3, 5"))
                .run(PdfOps::pdfToImages));

        ALL.add(new Tool("pdf-to-text", "PDF to Text", "Extract all text from a PDF", "convert", 0xFF14B8A6, 0xFF22C55E, R.drawable.ic_pdf_to_text)
                .cta("Extract text")
                .opt(Tool.Opt.toggle("marks", "Mark page breaks (— Page 2 —)", false))
                .run(PdfOps::pdfToText));

        ALL.add(new Tool("text-to-pdf", "Text to PDF", "Write or paste text, get a PDF", "convert", 0xFF0EA5E9, 0xFF6366F1, R.drawable.ic_text_to_pdf)
                .noFiles().extra(Tool.Extra.TEXT).cta("Create PDF")
                .opt(Tool.Opt.select("font", "Font", "sans", "sans", "Sans-serif", "serif", "Serif", "mono", "Monospace"))
                .opt(Tool.Opt.slider("size", "Font size", 12, 8, 32, " pt"))
                .opt(Tool.Opt.select("page", "Page size", "a4", "a4", "A4", "letter", "US Letter", "legal", "US Legal", "a5", "A5"))
                .opt(Tool.Opt.select("margin", "Margins", "56", "36", "Narrow", "56", "Normal", "80", "Wide"))
                .run(PdfOps::textToPdf));

        /* ------------------------------- edit ------------------------------- */
        ALL.add(new Tool("page-numbers", "Add Page Numbers", "Stamp numbers on every page", "edit", 0xFF6366F1, 0xFF06B6D4, R.drawable.ic_page_numbers)
                .cta("Add page numbers")
                .opt(Tool.Opt.select("pos", "Position", "bc", "bc", "Bottom center", "br", "Bottom right", "bl", "Bottom left", "tc", "Top center", "tr", "Top right", "tl", "Top left"))
                .opt(Tool.Opt.select("fmt", "Format", "{n}", "{n}", "1, 2, 3", "Page {n}", "Page 1", "{n} / {total}", "1 / 10", "Page {n} of {total}", "Page 1 of 10", "- {n} -", "- 1 -"))
                .opt(Tool.Opt.number("start", "Start numbering at", "1"))
                .opt(Tool.Opt.number("from", "Begin on page", "1"))
                .opt(Tool.Opt.slider("size", "Font size", 11, 6, 28, " pt"))
                .opt(Tool.Opt.color("color", "Colour", "#374151", "#374151", "Grey", "#111827", "Black", "#2563EB", "Blue", "#E11D48", "Red", "#16A34A", "Green"))
                .run(PdfOps::pageNumbers));

        ALL.add(new Tool("watermark", "Add Watermark", "Stamp text such as CONFIDENTIAL", "edit", 0xFFD946EF, 0xFF8B5CF6, R.drawable.ic_watermark)
                .cta("Add watermark")
                .opt(Tool.Opt.text("text", "Watermark text", "CONFIDENTIAL"))
                .opt(Tool.Opt.color("color", "Colour", "#E11D48", COLORS))
                .opt(Tool.Opt.slider("opacity", "Opacity", 25, 5, 90, "%"))
                .opt(Tool.Opt.slider("angle", "Rotation", 45, 0, 90, "°"))
                .opt(Tool.Opt.slider("size", "Size (width of page)", 65, 15, 95, "%"))
                .opt(Tool.Opt.toggle("tile", "Repeat across the page", false))
                .run(PdfOps::watermark));

        ALL.add(new Tool("sign", "Sign PDF", "Draw or type your signature", "edit", 0xFFF43F5E, 0xFFF59E0B, R.drawable.ic_sign)
                .popular().extra(Tool.Extra.SIGN).cta("Sign and save").run(PdfOps::sign));

        ALL.add(new Tool("metadata", "Edit Properties", "Title, author, subject, keywords", "edit", 0xFF84CC16, 0xFF14B8A6, R.drawable.ic_metadata)
                .extra(Tool.Extra.METADATA).cta("Save properties").run(PdfOps::metadata));

        /* ------------------------------- security ------------------------------- */
        ALL.add(new Tool("protect", "Protect PDF", "Add a password (AES encryption)", "security", 0xFF4F46E5, 0xFF7C3AED, R.drawable.ic_protect)
                .cta("Protect with password")
                .opt(Tool.Opt.password("password", "Password"))
                .opt(Tool.Opt.password("confirm", "Confirm password"))
                .opt(Tool.Opt.toggle("print", "Allow printing", true))
                .opt(Tool.Opt.toggle("copy", "Allow copying text", true))
                .run(PdfOps::protect));

        ALL.add(new Tool("unlock", "Unlock PDF", "Remove a password you know", "security", 0xFF0EA5E9, 0xFF22C55E, R.drawable.ic_unlock)
                .cta("Unlock PDF")
                .opt(Tool.Opt.password("password", "Current password"))
                .run(PdfOps::unlock));
    }

    private Tools() {
    }

    public static Tool byId(String id) {
        for (Tool t : ALL) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }
}
