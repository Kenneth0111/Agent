package com.example.creator.material;

import java.io.IOException;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
class PdfTextExtractor {
    static final long MAX_BYTES = 5L * 1024 * 1024;

    String extract(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new TextExtractor.MaterialInvalid("INVALID_PDF");
        }
        if (file.getSize() > MAX_BYTES) throw new TextExtractor.MaterialInvalid("MATERIAL_TOO_LARGE");
        try (var document = Loader.loadPDF(file.getBytes())) {
            var content = new PDFTextStripper().getText(document);
            if (content.isBlank()) throw new TextExtractor.MaterialInvalid("PDF_TEXT_UNAVAILABLE");
            return content;
        } catch (IOException | IllegalArgumentException invalid) {
            throw new TextExtractor.MaterialInvalid("INVALID_PDF");
        }
    }
}
