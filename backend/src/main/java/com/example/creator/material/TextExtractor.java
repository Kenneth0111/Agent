package com.example.creator.material;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TextExtractor {
    static final int MAX_BYTES = 100 * 1024;
    private static final int SEGMENT_CODE_POINTS = 1000;

    Extracted extract(String source) {
        if (source == null || source.isBlank() || source.indexOf('\0') >= 0) {
            throw new MaterialInvalid("INVALID_MATERIAL");
        }
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new MaterialInvalid("MATERIAL_TOO_LARGE");
        }
        var content = source.strip();
        var segments = new ArrayList<String>();
        for (var paragraph : content.split("(?:\\R\\s*){2,}")) {
            var part = paragraph.strip();
            for (int offset = 0; offset < part.length();) {
                int length = Math.min(SEGMENT_CODE_POINTS, part.codePointCount(offset, part.length()));
                int end = part.offsetByCodePoints(offset, length);
                segments.add(part.substring(offset, end));
                offset = end;
            }
        }
        return new Extracted(content, List.copyOf(segments));
    }

    record Extracted(String content, List<String> segments) { }

    static final class MaterialInvalid extends RuntimeException {
        MaterialInvalid(String code) { super(code); }
    }
}
