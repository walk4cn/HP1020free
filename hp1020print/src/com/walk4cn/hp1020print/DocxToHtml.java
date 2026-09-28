package com.walk4cn.hp1020print;

import org.zwobble.mammoth.DocumentConverter;
import org.zwobble.mammoth.Result;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * docx → HTML（用 mammoth，BSD-2，零运行时依赖）。
 *
 * mammoth 做的是「语义化转换」：按 Word 的样式语义生成干净的 HTML，
 * 不还原字体/字号/颜色，表格边框也交给 CSS 重新画。这对打印反而是好事——
 * 排版统一交给 WebView，我们不用自己处理中文换行和分页。
 */
public final class DocxToHtml {

    /*
     * Android 没有 StAX（javax.xml.stream），我们用 Aalto 补上。
     * 注意：jar 转 dex 时 META-INF/services 不会进 APK，ServiceLoader 找不到实现，
     * 所以必须显式指认工厂实现类（这和 poi-on-android 的做法一致）。
     */
    static {
        try {
            System.setProperty("javax.xml.stream.XMLOutputFactory",
                    "com.fasterxml.aalto.stax.OutputFactoryImpl");
            System.setProperty("javax.xml.stream.XMLInputFactory",
                    "com.fasterxml.aalto.stax.InputFactoryImpl");
            System.setProperty("javax.xml.stream.XMLEventFactory",
                    "com.fasterxml.aalto.stax.EventFactoryImpl");
        } catch (Throwable ignored) {
        }
    }

    /** 判断是不是 OOXML 的 Word 文档（zip 里有 word/document.xml） */
    public static boolean isDocx(File f) {
        ZipFile z = null;
        try {
            z = new ZipFile(f);
            ZipEntry e = z.getEntry("word/document.xml");
            return e != null && !e.isDirectory();
        } catch (Exception e) {
            return false;
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 转出 HTML 片段（只有 body 里的内容），套外壳见 {@link #wrap} */
    public static String convert(File docx) throws IOException {
        InputStream in = new FileInputStream(docx);
        String body;
        try {
            Result<String> r = new DocumentConverter().convertToHtml(in);
            body = r.getValue();
            Set<String> w = r.getWarnings();
            if (w != null && !w.isEmpty()) {
                StringBuilder sb = new StringBuilder("<!-- mammoth warnings:\n");
                for (String s : w) sb.append(s).append('\n');
                sb.append("-->");
                body = body + sb;
            }
        } finally {
            in.close();
        }
        return body;
    }

    /**
     * 给 HTML 片段套上打印用的外壳。
     * 图片已被 mammoth 转成 data: URI 内嵌，所以完全离线、不依赖网络。
     */
    public static String wrap(String body, String paper) {
        String size = "A4";
        if (Prefs.PAPER_LETTER.equals(paper)) size = "Letter";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">\n"
                + "<style>\n"
                + "@page { size: " + size + "; margin: 18mm 20mm; }\n"
                + "html,body { margin:0; padding:0; }\n"
                + "body { font-family: serif; font-size: 11pt; line-height: 1.5; color:#000; }\n"
                + "h1 { font-size: 20pt; margin: 14pt 0 8pt; }\n"
                + "h2 { font-size: 16pt; margin: 12pt 0 6pt; }\n"
                + "h3,h4,h5,h6 { font-size: 13pt; margin: 10pt 0 6pt; }\n"
                + "p { margin: 0 0 7pt; }\n"
                + "ul,ol { margin: 0 0 7pt; padding-left: 14mm; }\n"
                + "li { margin: 0 0 3pt; }\n"
                + "table { border-collapse: collapse; width: 100%; margin: 8pt 0; }\n"
                + "th,td { border: 1px solid #666; padding: 3pt 5pt; font-size: 10.5pt; }\n"
                + "img { max-width: 100%; height: auto; }\n"
                + "pre { font-family: monospace; white-space: pre-wrap; font-size: 9.5pt; }\n"
                + "blockquote { margin: 6pt 0 6pt 8mm; color:#222; }\n"
                + "a { color:#000; text-decoration: underline; }\n"
                + "</style></head><body>\n"
                + (body == null ? "" : body)
                + "\n</body></html>";
    }

    private DocxToHtml() {
    }
}
