package com.sei.nexus.attachment;

import com.sei.nexus.ai.AzureOpenAiClient;
import com.sei.nexus.common.Keys;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Processes uploaded files and pasted images for use in chat conversations.
 *
 * <p>Supports:
 * <ul>
 *   <li><b>Images</b> (JPEG, PNG, WebP, GIF, BMP, TIFF) — sent to GPT-4o Vision for extraction</li>
 *   <li><b>Tabular data</b> (CSV, TSV, Excel .xlsx/.xls) — parsed into readable text tables</li>
 *   <li><b>Documents</b> (PDF, DOCX, PPTX, ODT) — text extracted via Apache Tika</li>
 *   <li><b>Plain text</b> (TXT, MD, JSON, XML, HTML, LOG) — read directly</li>
 * </ul>
 *
 * <p>All extracted content is scoped to the conversation and auto-expires after 24 hours.
 */
@Service
public class AttachmentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentProcessingService.class);

    private static final int MAX_EXTRACTED_CHARS   = 40_000;
    private static final int THUMBNAIL_MAX_PX      = 512;
    private static final int MAX_TABULAR_ROWS_DISPLAY = 200;

    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "image/bmp", "image/tiff", "image/x-png");

    private static final Set<String> TABULAR_TYPES = Set.of(
            "text/csv", "text/tab-separated-values",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/csv");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "json", "xml", "html", "htm", "log", "yaml", "yml",
            "toml", "ini", "conf", "properties", "sql");

    private static final Set<String> TABULAR_EXTENSIONS = Set.of(
            "csv", "tsv", "xlsx", "xls");

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff", "tif");

    private final AzureOpenAiClient        aiClient;
    private final ChatAttachmentRepository repository;

    public AttachmentProcessingService(AzureOpenAiClient aiClient,
                                        ChatAttachmentRepository repository) {
        this.aiClient   = aiClient;
        this.repository = repository;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Processes an uploaded file and persists it as a chat attachment.
     * Returns the persisted {@link ChatAttachment} ready to be referenced in chat.
     */
    public ChatAttachment process(String originalFileName,
                                   String mimeType,
                                   byte[] bytes,
                                   String conversationId,
                                   String userEmail) {
        String ext          = extension(originalFileName).toLowerCase();
        String resolvedMime = resolveMimeType(mimeType, ext, bytes);
        String type         = classifyType(resolvedMime, ext);

        log.info("Processing attachment '{}' ({}, {}, {} bytes) for conversation {}",
                originalFileName, type, resolvedMime, bytes.length, conversationId);

        String extracted;
        String summary;
        String thumbnail = null;

        try {
            extracted = switch (type) {
                case "IMAGE"    -> extractFromImage(bytes, resolvedMime);
                case "TABULAR"  -> extractFromTabular(bytes, resolvedMime, ext);
                case "DOCUMENT" -> extractFromDocument(bytes);
                default         -> extractFromText(bytes);
            };

            if ("IMAGE".equals(type)) {
                thumbnail = buildThumbnail(bytes, resolvedMime);
            }

            summary = buildSummary(originalFileName, type, extracted, resolvedMime);

        } catch (Exception e) {
            log.error("Attachment processing failed for '{}': {}", originalFileName, e.getMessage());
            extracted = "File could not be processed: " + e.getMessage();
            summary   = "Attachment: " + originalFileName + " (processing failed)";
        }

        // Trim to context window limit
        if (extracted.length() > MAX_EXTRACTED_CHARS) {
            extracted = extracted.substring(0, MAX_EXTRACTED_CHARS)
                    + "\n\n[Content truncated — showing first "
                    + MAX_EXTRACTED_CHARS + " characters of " + bytes.length + " bytes total]";
        }

        ChatAttachment attachment = new ChatAttachment(
                Keys.uniqueKey("att"),
                conversationId,
                originalFileName,
                type,
                resolvedMime,
                (long) bytes.length,
                summary,
                extracted,
                thumbnail,
                userEmail,
                Instant.now(),
                Instant.now().plus(24, ChronoUnit.HOURS));

        repository.save(attachment);
        return attachment;
    }

    // ── Image extraction via GPT-4o Vision ───────────────────────────────────

    private String extractFromImage(byte[] bytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String vision = aiClient.analyzeImage(
                null,
                base64,
                mimeType,
                """
                You are extracting information from an image for an enterprise data analysis platform.
                Extract ALL visible text, numbers, dates, tables, and structured information.
                Format tables as: | Column1 | Column2 | Column3 |
                Be comprehensive and precise — include every piece of data you can see.
                If this is a structured document, extract all fields including dates, amounts, reference numbers, names, and line items.
                If this is a chart or graph, describe the data values and trends.
                If this is a screenshot, extract all visible text and numbers.
                """);
        return "[Image content extracted by AI vision]\n\n" + vision;
    }

    // ── Tabular data extraction (CSV, TSV, Excel) ─────────────────────────────

    private String extractFromTabular(byte[] bytes, String mimeType, String ext) throws Exception {
        if ("xlsx".equals(ext) || "xls".equals(ext)
                || mimeType.contains("spreadsheetml")
                || mimeType.contains("excel")) {
            return extractFromExcel(bytes);
        }
        return extractFromCsv(bytes, "\t".equals(ext) ? '\t' : ',');
    }

    private String extractFromExcel(byte[] bytes) throws Exception {
        try (InputStream is = new ByteArrayInputStream(bytes);
             Workbook wb = new XSSFWorkbook(is)) {

            StringBuilder sb = new StringBuilder();
            DataFormatter formatter = new DataFormatter();

            for (int sheetIdx = 0; sheetIdx < wb.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = wb.getSheetAt(sheetIdx);
                if (wb.getNumberOfSheets() > 1) {
                    sb.append("## Sheet: ").append(sheet.getSheetName()).append("\n\n");
                }

                int rowCount = 0;
                for (Row row : sheet) {
                    if (rowCount++ > MAX_TABULAR_ROWS_DISPLAY) {
                        sb.append("[... ").append(sheet.getLastRowNum() - MAX_TABULAR_ROWS_DISPLAY)
                          .append(" more rows]\n");
                        break;
                    }
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(formatter.formatCellValue(cell));
                    }
                    sb.append(String.join(" | ", cells)).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        }
    }

    private String extractFromCsv(byte[] bytes, char delimiter) {
        String content = new String(bytes);
        String[] lines = content.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        int shown = 0;

        for (String line : lines) {
            if (shown++ > MAX_TABULAR_ROWS_DISPLAY) {
                sb.append("[... ").append(lines.length - MAX_TABULAR_ROWS_DISPLAY).append(" more rows]\n");
                break;
            }
            // Parse quoted CSV properly
            List<String> fields = parseCsvLine(line, delimiter);
            sb.append(String.join(" | ", fields)).append("\n");
        }
        return sb.toString().trim();
    }

    private List<String> parseCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes    = false;
        StringBuilder field = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                fields.add(field.toString().trim());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString().trim());
        return fields;
    }

    // ── Document extraction via Apache Tika ──────────────────────────────────

    private String extractFromDocument(byte[] bytes) {
        try {
            Tika tika = new Tika();
            tika.setMaxStringLength(MAX_EXTRACTED_CHARS);
            return tika.parseToString(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            log.warn("Tika extraction failed: {}", e.getMessage());
            return "Document could not be parsed: " + e.getMessage();
        }
    }

    // ── Plain text ────────────────────────────────────────────────────────────

    private String extractFromText(byte[] bytes) {
        return new String(bytes);
    }

    // ── Thumbnail generation ──────────────────────────────────────────────────

    private String buildThumbnail(byte[] bytes, String mimeType) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original == null) return null;

            int w = original.getWidth(), h = original.getHeight();
            double scale = Math.min((double) THUMBNAIL_MAX_PX / w, (double) THUMBNAIL_MAX_PX / h);
            int tw = (int) (w * scale), th = (int) (h * scale);

            BufferedImage thumb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = thumb.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(original, 0, 0, tw, th, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumb, "jpeg", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.warn("Thumbnail generation failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private String buildSummary(String fileName, String type,
                                 String extracted, String mimeType) {
        int lines = extracted.split("\n").length;
        int chars = extracted.length();
        return switch (type) {
            case "IMAGE"    -> "Image: " + fileName + " — AI vision extracted "
                    + chars + " characters of content";
            case "TABULAR"  -> "Spreadsheet/CSV: " + fileName + " — "
                    + lines + " rows extracted";
            case "DOCUMENT" -> "Document: " + fileName + " — "
                    + lines + " lines, " + chars + " characters extracted";
            default         -> "Text file: " + fileName + " — "
                    + lines + " lines";
        };
    }

    // ── Classification helpers ────────────────────────────────────────────────

    private String classifyType(String mimeType, String ext) {
        if (IMAGE_TYPES.contains(mimeType)     || IMAGE_EXTENSIONS.contains(ext))   return "IMAGE";
        if (TABULAR_TYPES.contains(mimeType)   || TABULAR_EXTENSIONS.contains(ext)) return "TABULAR";
        if (TEXT_EXTENSIONS.contains(ext))                                           return "TEXT";
        // PDFs, DOCX, PPTX, etc.
        return "DOCUMENT";
    }

    private String resolveMimeType(String declared, String ext, byte[] bytes) {
        if (declared != null && !declared.isBlank()
                && !"application/octet-stream".equals(declared)) {
            return declared;
        }
        // Detect from magic bytes
        if (bytes.length >= 4) {
            if (bytes[0] == (byte)0xFF && bytes[1] == (byte)0xD8) return "image/jpeg";
            if (bytes[0] == (byte)0x89 && bytes[1] == 'P')        return "image/png";
            if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
            if (bytes[0] == (byte)0x25 && bytes[1] == (byte)0x50) return "application/pdf";
            if (bytes[0] == 'P' && bytes[1] == 'K')               return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return switch (ext) {
            case "csv"  -> "text/csv";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls"  -> "application/vnd.ms-excel";
            case "pdf"  -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png"  -> "image/png";
            case "webp" -> "image/webp";
            default     -> "text/plain";
        };
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "";
    }

    // ── Cleanup scheduler ─────────────────────────────────────────────────────

    /** Runs every hour, removes expired attachments. */
    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupExpired() {
        int deleted = repository.deleteExpired();
        if (deleted > 0) log.info("Cleaned up {} expired chat attachments", deleted);
    }
}
