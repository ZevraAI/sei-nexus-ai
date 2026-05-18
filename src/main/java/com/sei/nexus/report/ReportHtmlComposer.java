package com.sei.nexus.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Assembles a production-quality HTML email report from a list of report sections.
 * Each section corresponds to one question asked during the report run.
 */
@Component
public class ReportHtmlComposer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z");

    @Value("${nexus.alerts.app-url:http://localhost:5176}")
    private String appUrl;

    // ── Public API ────────────────────────────────────────────────────────────

    public String composeEmail(ScheduledReport report, List<ReportSection> sections, String scheduleDescription) {
        StringBuilder sectionsHtml = new StringBuilder();
        for (ReportSection s : sections) {
            sectionsHtml.append(renderSection(s));
        }

        String generatedAt = DATE_FMT.format(
                ZonedDateTime.now(safeZone(report.timezone())));

        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="UTF-8"/>
                <meta name="viewport" content="width=device-width,initial-scale=1"/>
                <title>%s</title>
                </head>
                <body style="margin:0;padding:0;background:#F5F5FA;font-family:'Inter',Arial,Helvetica,sans-serif;color:#111827">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background:#F5F5FA;padding:36px 0">
                    <tr><td align="center">
                      <table width="660" cellpadding="0" cellspacing="0"
                             style="background:white;border-radius:16px;overflow:hidden;
                                    box-shadow:0 8px 40px rgba(0,0,0,0.10)">

                        <!-- Header -->
                        <tr><td style="background:linear-gradient(135deg,#0D1117 0%%,#1C2A20 100%%);padding:32px 40px">
                          <table width="100%%" cellpadding="0" cellspacing="0"><tr>
                            <td>
                              <div style="display:inline-flex;align-items:center;gap:10px;margin-bottom:16px">
                                <div style="width:32px;height:32px;background:linear-gradient(135deg,#059669,#047857);
                                            border-radius:8px;display:inline-block;vertical-align:middle;
                                            text-align:center;line-height:32px;font-size:16px">✦</div>
                                <span style="font-size:16px;font-weight:700;color:white;vertical-align:middle;
                                             letter-spacing:-0.01em;margin-left:8px">Zevra</span>
                              </div>
                              <h1 style="margin:0 0 6px;font-size:24px;font-weight:800;color:white;
                                         letter-spacing:-0.02em;line-height:1.2">%s</h1>
                              <p style="margin:0;font-size:13px;color:rgba(255,255,255,0.55)">
                                %s &nbsp;·&nbsp; %s
                              </p>
                            </td>
                          </tr></table>
                        </td></tr>

                        <!-- Meta bar -->
                        <tr><td style="background:#F9FAFB;border-bottom:1px solid #E5E7EB;
                                       padding:12px 40px;font-size:12px;color:#6B7280">
                          <table width="100%%" cellpadding="0" cellspacing="0"><tr>
                            <td><strong style="color:#374151">Schedule:</strong> %s</td>
                            <td align="right"><strong style="color:#374151">Sections:</strong> %d</td>
                          </tr></table>
                        </td></tr>

                        <!-- Sections -->
                        <tr><td style="padding:32px 40px 0">
                          %s
                        </td></tr>

                        <!-- Footer -->
                        <tr><td style="padding:28px 40px 32px;border-top:1px solid #F3F4F6;
                                       font-size:12px;color:#9CA3AF;margin-top:20px">
                          <table width="100%%" cellpadding="0" cellspacing="0"><tr>
                            <td>
                              This report was generated automatically by Zevra.<br/>
                              <a href="%s/#/reports" style="color:#059669;text-decoration:none">
                                Manage scheduled reports →
                              </a>
                            </td>
                            <td align="right">
                              <a href="%s/#/chat" style="display:inline-block;background:#111827;
                                 color:white;padding:10px 20px;border-radius:8px;
                                 text-decoration:none;font-size:13px;font-weight:600">
                                Investigate in Zevra →
                              </a>
                            </td>
                          </tr></table>
                        </td></tr>

                      </table>
                    </td></tr>
                  </table>
                </body></html>
                """.formatted(
                escHtml(report.name()),
                escHtml(report.name()),
                escHtml(report.description() != null ? report.description() : "Automated report"),
                escHtml(generatedAt),
                escHtml(scheduleDescription),
                sections.size(),
                sectionsHtml,
                appUrl, appUrl);
    }

    /** Plain-text summary for Slack — one finding per section. */
    public String composeSlackText(ScheduledReport report, List<ReportSection> sections, String scheduleDescription) {
        StringBuilder sb = new StringBuilder();
        sb.append("*📊 ").append(escSlack(report.name())).append("*\n");
        sb.append("_").append(escSlack(scheduleDescription)).append("_\n\n");
        for (ReportSection s : sections) {
            sb.append("*").append(s.sectionNumber()).append(". ").append(escSlack(s.question())).append("*\n");
            String summary = s.answer() != null && s.answer().length() > 300
                    ? s.answer().substring(0, 297) + "…"
                    : s.answer();
            if (summary != null) sb.append(escSlack(summary)).append("\n");
            if (s.queryData() != null && !s.queryData().isEmpty()) {
                sb.append("_").append(s.queryData().size()).append(" rows returned_\n");
            }
            sb.append("\n");
        }
        sb.append("<").append(appUrl).append("/#/chat|Open in Zevra →>");
        return sb.toString();
    }

    // ── Section rendering ─────────────────────────────────────────────────────

    private String renderSection(ReportSection s) {
        StringBuilder html = new StringBuilder();

        // Section header
        html.append("""
                <div style="margin-bottom:28px">
                  <div style="display:flex;align-items:center;gap:10px;margin-bottom:12px">
                    <span style="display:inline-block;width:24px;height:24px;background:#0C5847;
                                 border-radius:6px;color:white;font-size:11px;font-weight:700;
                                 text-align:center;line-height:24px">%d</span>
                    <h2 style="margin:0;font-size:15px;font-weight:700;color:#111827">%s</h2>
                  </div>
                """.formatted(s.sectionNumber(), escHtml(s.question())));

        // Answer prose
        if (s.answer() != null && !s.answer().isBlank()) {
            html.append("""
                    <div style="background:#F9FAFB;border:1px solid #E5E7EB;border-radius:10px;
                                padding:16px 18px;margin-bottom:14px;font-size:14px;
                                color:#374151;line-height:1.65">
                      %s
                    </div>
                    """.formatted(escHtml(s.answer()).replace("\n", "<br/>")));
        }

        // Data table
        if (s.queryData() != null && !s.queryData().isEmpty()) {
            html.append(renderTable(s.queryData()));
        }

        html.append("</div>\n");
        return html.toString();
    }

    private String renderTable(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "";
        List<String> cols = List.copyOf(rows.get(0).keySet());

        StringBuilder t = new StringBuilder();
        t.append("""
                <div style="overflow-x:auto;margin-bottom:8px;border-radius:10px;
                            border:1px solid #E5E7EB;overflow:hidden">
                  <table width="100%%" cellpadding="0" cellspacing="0"
                         style="border-collapse:collapse;font-size:12.5px">
                    <thead>
                      <tr style="background:#0C5847">
                """);

        for (String col : cols) {
            t.append("<th style=\"padding:9px 14px;text-align:left;color:rgba(255,255,255,0.9);"
                    + "font-size:11px;font-weight:600;letter-spacing:0.04em;white-space:nowrap\">")
             .append(escHtml(labelFor(col)))
             .append("</th>");
        }
        t.append("</tr></thead><tbody>");

        int maxRows = Math.min(rows.size(), 20);
        for (int i = 0; i < maxRows; i++) {
            String rowBg = i % 2 == 0 ? "white" : "#F9FAFB";
            t.append("<tr style=\"background:").append(rowBg).append(";border-bottom:1px solid #F3F4F6\">");
            for (String col : cols) {
                Object v = rows.get(i).get(col);
                t.append("<td style=\"padding:9px 14px;color:#374151\">")
                 .append(v != null ? escHtml(v.toString()) : "—")
                 .append("</td>");
            }
            t.append("</tr>");
        }

        t.append("</tbody>");
        if (rows.size() > 20) {
            t.append("<tfoot><tr><td colspan=\"").append(cols.size())
             .append("\" style=\"padding:8px 14px;font-size:11px;color:#9CA3AF;background:#F9FAFB\">")
             .append("Showing 20 of ").append(rows.size()).append(" rows")
             .append("</td></tr></tfoot>");
        } else {
            t.append("<tfoot><tr><td colspan=\"").append(cols.size())
             .append("\" style=\"padding:7px 14px;font-size:11px;color:#9CA3AF;background:#F9FAFB\">")
             .append(rows.size()).append(" row").append(rows.size() != 1 ? "s" : "")
             .append("</td></tr></tfoot>");
        }
        t.append("</table></div>");
        return t.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String labelFor(String col) {
        if (col == null) return "";
        return col.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2")
                  .substring(0, 1).toUpperCase()
                  + (col.length() > 1 ? col.replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2").substring(1) : "");
    }

    private ZoneId safeZone(String tz) {
        try { return tz != null ? ZoneId.of(tz) : ZoneId.of("UTC"); }
        catch (Exception e) { return ZoneId.of("UTC"); }
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String escSlack(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
