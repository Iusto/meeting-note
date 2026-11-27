package dev.aimeeting.api.export;

import java.io.ByteArrayOutputStream;
import java.util.List;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;

final class HwpMeetingDocument {
    private HwpMeetingDocument() {}

    static byte[] write(List<MeetingDocuments.Paragraph> document) throws Exception {
        var file = BlankFileMaker.make();
        var section = file.getBodyText().getSectionList().get(0);
        var shapes = file.getDocInfo().getCharShapeList();
        int titleShape = shapes.size();
        var title = shapes.getFirst().clone();
        title.setBaseSize(MeetingDocuments.Kind.TITLE.halfPoints * 50);
        shapes.add(title);
        int headingShape = shapes.size();
        var heading = shapes.getFirst().clone();
        heading.setBaseSize(MeetingDocuments.Kind.HEADING.halfPoints * 50);
        shapes.add(heading);
        for (int index = 0; index < document.size(); index++) {
            var line = document.get(index);
            var paragraph = index == 0 ? section.getParagraph(0) : section.addNewParagraph();
            if (index == 0) {
                paragraph.deleteLineSeg();
            } else {
                paragraph.getHeader().setParaShapeId(3);
                paragraph.getHeader().setStyleId((short) 0);
                paragraph.createText();
            }
            int shape = switch (line.kind()) {
                case TITLE -> titleShape;
                case HEADING -> headingShape;
                case BODY -> 0;
            };
            paragraph.createCharShape();
            paragraph.getCharShape().addParaCharShape(0, shape);
            var text = paragraph.getText();
            // Write UTF-16 code units so supplementary characters retain both surrogates.
            for (char code : line.text().toCharArray()) {
                text.addNewNormalChar().setCode(code);
            }
            text.addNewNormalChar().setCode(0x0d);
        }
        var bytes = new ByteArrayOutputStream();
        HWPWriter.toStream(file, bytes);
        return bytes.toByteArray();
    }
}
