package org.arghyam.jalsoochak.message.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Renders a PDF as stable, readable text for snapshot comparison: every content-stream operation of
 * every page, then the page's annotations.
 *
 * <p>The file's bytes can't be compared: each save writes a fresh document id. What a reader sees is
 * fully determined by the content streams and the link annotations, so those are what is recorded.
 * Strings are decoded to Unicode through the font selected by the preceding {@code Tf}, so a diff
 * reads as text rather than glyph ids. A text object ({@code BT … ET}) is kept on one line.</p>
 */
final class PdfContentSnapshot {

    private PdfContentSnapshot() {
    }

    static String of(Path pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            StringBuilder out = new StringBuilder();
            int pageNo = 0;
            for (PDPage page : doc.getPages()) {
                out.append("=== page ").append(++pageNo)
                        .append(" mediaBox=").append(rect(page.getMediaBox())).append('\n');
                appendContent(page, out);
                appendAnnotations(page, out);
            }
            return out.toString();
        }
    }

    private static void appendContent(PDPage page, StringBuilder out) throws IOException {
        PDResources resources = page.getResources();
        PDFont font = null;
        List<Object> operands = new ArrayList<>();
        StringJoiner textObject = null;

        for (Object token : new PDFStreamParser(page).parse()) {
            if (!(token instanceof Operator operator)) {
                operands.add(token);
                continue;
            }
            String name = operator.getName();
            if ("Tf".equals(name) && !operands.isEmpty() && operands.get(0) instanceof COSName fontName) {
                font = resources.getFont(fontName);
            }
            String operation = operation(operands, name, font);
            operands.clear();

            if ("BT".equals(name)) {
                textObject = new StringJoiner(" | ");
                textObject.add(operation);
            } else if (textObject != null) {
                textObject.add(operation);
                if ("ET".equals(name)) {
                    out.append(textObject).append('\n');
                    textObject = null;
                }
            } else {
                out.append(operation).append('\n');
            }
        }
        if (textObject != null) {
            out.append(textObject).append('\n');
        }
    }

    private static String operation(List<Object> operands, String operator, PDFont font) throws IOException {
        StringJoiner line = new StringJoiner(" ");
        for (Object operand : operands) {
            line.add(operand(operand, font));
        }
        line.add(operator);
        return line.toString();
    }

    private static String operand(Object operand, PDFont font) throws IOException {
        if (operand instanceof COSString s) {
            return font != null ? quote(decode(s.getBytes(), font)) : "<" + s.toHexString() + ">";
        }
        if (operand instanceof COSInteger i) {
            return Long.toString(i.longValue());
        }
        if (operand instanceof COSFloat f) {
            return Float.toString(f.floatValue());
        }
        if (operand instanceof COSName n) {
            return "/" + n.getName();
        }
        if (operand instanceof COSArray array) {
            StringJoiner items = new StringJoiner(" ", "[", "]");
            for (COSBase item : array) {
                items.add(operand(item, font));
            }
            return items.toString();
        }
        return String.valueOf(operand);
    }

    private static String decode(byte[] bytes, PDFont font) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            while (in.available() > 0) {
                int code = font.readCode(in);
                String unicode = font.toUnicode(code);
                text.append(unicode != null ? unicode : "<?" + code + ">");
            }
        }
        return text.toString();
    }

    private static void appendAnnotations(PDPage page, StringBuilder out) throws IOException {
        for (PDAnnotation annotation : page.getAnnotations()) {
            out.append("annotation ").append(annotation.getSubtype())
                    .append(" rect=").append(rect(annotation.getRectangle()));
            if (annotation instanceof PDAnnotationLink link) {
                PDAction action = link.getAction();
                if (action instanceof PDActionURI uri) {
                    out.append(" uri=").append(uri.getURI());
                }
                PDBorderStyleDictionary border = link.getBorderStyle();
                if (border != null) {
                    out.append(" border=").append(border.getWidth());
                }
            }
            out.append('\n');
        }
    }

    private static String rect(PDRectangle r) {
        return r == null ? "none" : "[" + r.getLowerLeftX() + " " + r.getLowerLeftY() + " "
                + r.getUpperRightX() + " " + r.getUpperRightY() + "]";
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
