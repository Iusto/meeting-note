package dev.aimeeting.api.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.List;
import kr.dogfoot.hwplib.reader.HWPReader;
import org.junit.jupiter.api.Test;

class HwpMeetingDocumentTest {
    @Test
    void readsBackKoreanTitleAndParagraphs() throws Exception {
        var paragraphs = List.of(
                new MeetingDocuments.Paragraph(MeetingDocuments.Kind.TITLE, "기획 회의"),
                new MeetingDocuments.Paragraph(MeetingDocuments.Kind.HEADING, "핵심 요약"),
                new MeetingDocuments.Paragraph(MeetingDocuments.Kind.BODY, "다음 회의 전 검토"));
        byte[] bytes = HwpMeetingDocument.write(paragraphs);
        assertThat(bytes).startsWith((byte) 0xd0, (byte) 0xcf, (byte) 0x11, (byte) 0xe0);
        var section = HWPReader.fromInputStream(new ByteArrayInputStream(bytes)).getBodyText().getSectionList().get(0);
        for (int index = 0; index < paragraphs.size(); index++) {
            var text = section.getParagraph(index).getText();
            assertThat(text.getNormalString(0)).contains(paragraphs.get(index).text());
        }
    }
}
