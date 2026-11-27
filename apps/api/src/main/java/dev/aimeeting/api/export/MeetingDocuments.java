package dev.aimeeting.api.export;

import dev.aimeeting.api.export.MeetingExport.ExportTranscriptSegment;
import dev.aimeeting.api.provider.SummaryProvider.Evidence;
import dev.aimeeting.api.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class MeetingDocuments {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'KST'")
            .withZone(ZoneId.of("Asia/Seoul"));
    private final Semaphore pdfSlot = new Semaphore(1);

    byte[] render(MeetingExport snapshot, String format) {
        if (snapshot.transcript().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_TRANSCRIPT", "발언 기록이 생성되면 회의록을 다운로드할 수 있습니다.");
        }
        List<Paragraph> document = paragraphs(snapshot);
        try {
            return switch (format) {
                case "docx" -> docx(document);
                case "hwp" -> HwpMeetingDocument.write(document);
                case "pdf" -> pdf(docx(document));
                default -> throw new IllegalArgumentException("Unsupported document format");
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exportFailed(exception);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw exportFailed(exception);
        }
    }

    private static ApiException exportFailed(Exception cause) {
        var error = new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "DOCUMENT_EXPORT_FAILED",
                "회의록 파일을 생성하지 못했습니다.");
        error.initCause(cause);
        return error;
    }

    private static List<Paragraph> paragraphs(MeetingExport snapshot) {
        List<Paragraph> paragraphs = new ArrayList<>();
        add(paragraphs, Kind.TITLE, snapshot.meeting().title());
        if (snapshot.meeting().startedAt() != null) {
            add(paragraphs, Kind.BODY, "회의 일시  " + DATE.format(snapshot.meeting().startedAt()));
        }
        Map<UUID, String> timestamps = new HashMap<>();
        snapshot.transcript().forEach(segment -> timestamps.put(segment.id(), time(segment.startMs())));
        add(paragraphs, Kind.HEADING, "핵심 요약");
        var summary = snapshot.summary();
        if (summary == null) {
            add(paragraphs, Kind.BODY, "아직 저장된 요약이 없습니다.");
        } else {
            if (summary.stale()) add(paragraphs, Kind.BODY, "요약 생성 후 발언 기록이 수정되었습니다.");
            var content = summary.content();
            add(paragraphs, Kind.BODY, content.summary() + sources(content.sourceSegmentIds(), timestamps));
            appendEvidence(paragraphs, "주제별 논의", content.discussions(), timestamps);
            appendEvidence(paragraphs, "확정 결정", content.decisions(), timestamps);
            add(paragraphs, Kind.HEADING, "후속 조치");
            if (content.actionItems().isEmpty()) add(paragraphs, Kind.BODY, "기록 없음");
            for (var item : content.actionItems()) {
                add(paragraphs, Kind.BODY, "• " + item.text() + sources(item.sourceSegmentIds(), timestamps));
                add(paragraphs, Kind.BODY, "담당  " + value(item.assignee()) + "   |   기한  " + value(item.dueDate()));
            }
            appendEvidence(paragraphs, "확인할 사항", content.unresolved(), timestamps);
        }
        add(paragraphs, Kind.HEADING, "발언 기록");
        Map<String, Integer> speakers = new HashMap<>();
        for (ExportTranscriptSegment segment : snapshot.transcript()) {
            String speaker = "unknown".equals(segment.speaker()) ? "미지정 화자"
                    : "화자 " + speakers.computeIfAbsent(segment.speaker(), ignored -> speakers.size() + 1);
            add(paragraphs, Kind.BODY, time(segment.startMs()) + "  " + speaker + "  " + segment.displayText());
            if (!segment.displayText().equals(segment.originalText())) {
                add(paragraphs, Kind.BODY, "원문  " + segment.originalText());
            }
        }
        return paragraphs;
    }

    private static void appendEvidence(List<Paragraph> paragraphs, String heading, List<Evidence> items,
                                       Map<UUID, String> timestamps) {
        add(paragraphs, Kind.HEADING, heading);
        if (items.isEmpty()) add(paragraphs, Kind.BODY, "기록 없음");
        for (var item : items) add(paragraphs, Kind.BODY, "• " + item.text() + sources(item.sourceSegmentIds(), timestamps));
    }

    private static void add(List<Paragraph> paragraphs, Kind kind, String text) {
        for (String line : text.split("\\R", -1)) {
            var clean = new StringBuilder();
            line.codePoints().filter(cp -> cp >= 32 && !(cp >= 0xD800 && cp <= 0xDFFF)
                    && cp != 0xFFFE && cp != 0xFFFF).forEach(clean::appendCodePoint);
            paragraphs.add(new Paragraph(kind, clean.toString()));
        }
    }

    private static String sources(List<UUID> ids, Map<UUID, String> timestamps) {
        String times = ids.stream().filter(timestamps::containsKey).map(timestamps::get)
                .distinct().collect(Collectors.joining(", "));
        return times.isEmpty() ? "" : "  [근거 " + times + "]";
    }

    private static String value(String value) { return value == null || value.isBlank() ? "미정" : value; }

    private static String time(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private static byte[] docx(List<Paragraph> paragraphs) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            entry(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            var xml = new StringBuilder("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
            for (Paragraph paragraph : paragraphs) {
                Kind kind = paragraph.kind();
                xml.append("<w:p><w:pPr>");
                if (kind != Kind.BODY) xml.append("<w:keepNext/>");
                xml.append("<w:spacing w:before=\"").append(kind == Kind.HEADING ? "230" : "0")
                        .append("\" w:after=\"100\" w:line=\"290\" w:lineRule=\"auto\"/></w:pPr><w:r><w:rPr>")
                        .append("<w:rFonts w:ascii=\"Noto Sans CJK KR\" w:hAnsi=\"Noto Sans CJK KR\" w:eastAsia=\"Noto Sans CJK KR\"/>");
                if (kind != Kind.BODY) xml.append("<w:b/><w:color w:val=\"244B3C\"/>");
                xml.append("<w:sz w:val=\"").append(kind.halfPoints).append("\"/></w:rPr><w:t xml:space=\"preserve\">")
                        .append(escape(paragraph.text())).append("</w:t></w:r></w:p>");
            }
            xml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>");
            entry(zip, "word/document.xml", xml.toString());
        }
        return bytes.toByteArray();
    }

    private byte[] pdf(byte[] document) throws IOException, InterruptedException {
        if (!pdfSlot.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PDF_EXPORT_BUSY", "다른 PDF 변환이 진행 중입니다. 잠시 후 다시 시도하세요.");
        }
        Path temp = null;
        Process process = null;
        try {
            temp = Files.createTempDirectory("meeting-pdf-");
            Path source = Files.write(temp.resolve("minutes.docx"), document);
            process = new ProcessBuilder("soffice", "-env:UserInstallation=" + temp.resolve("profile").toUri(),
                    "--headless", "--convert-to", "pdf:writer_pdf_Export", "--outdir", temp.toString(), source.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Path output = temp.resolve("minutes.pdf");
            if (!process.waitFor(45, TimeUnit.SECONDS) || process.exitValue() != 0 || !Files.isRegularFile(output)) {
                throw new IOException("LibreOffice failed to create the PDF");
            }
            return Files.readAllBytes(output);
        } finally {
            try {
                if (process != null && process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                if (temp != null) {
                    try (var paths = Files.walk(temp)) {
                        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    }
                }
            } finally {
                pdfSlot.release();
            }
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    enum Kind {
        TITLE(42), HEADING(28), BODY(21);
        final int halfPoints;
        Kind(int halfPoints) { this.halfPoints = halfPoints; }
    }

    record Paragraph(Kind kind, String text) {}
}
