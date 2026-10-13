package com.example.creator.analytics;

import com.example.creator.auth.CurrentUser;
import java.io.IOException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/metrics/import")
public class ImportController {
    private final CurrentUser currentUser;
    private final ImportService imports;
    private final CommentImportService comments;

    ImportController(CurrentUser currentUser, ImportService imports, CommentImportService comments) {
        this.currentUser = currentUser;
        this.imports = imports;
        this.comments = comments;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> preview(@RequestParam MultipartFile file) {
        return upload(file, false);
    }

    @PostMapping(value = "/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> confirm(@RequestParam MultipartFile file) {
        return upload(file, true);
    }

    @PostMapping(value = "/comments/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> previewComments(@RequestParam MultipartFile file) {
        return uploadComments(file, false);
    }

    @PostMapping(value = "/comments/confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> confirmComments(@RequestParam MultipartFile file) {
        return uploadComments(file, true);
    }

    private ResponseEntity<?> uploadComments(MultipartFile file, boolean confirm) {
        if (file.getSize() > ImportService.MAX_BYTES) return tooLarge();
        try {
            var ownerId = currentUser.id();
            var bytes = file.getBytes();
            return ResponseEntity.ok(confirm ? comments.confirm(ownerId, bytes) : comments.preview(ownerId, bytes));
        } catch (CommentImportService.ImportRejected rejected) {
            return ResponseEntity.unprocessableEntity().body(rejected.preview());
        } catch (DataIntegrityViolationException changed) {
            return ResponseEntity.status(409).body(new ErrorView("IMPORT_CONFLICT_RETRY_PREVIEW"));
        } catch (IOException invalid) {
            return ResponseEntity.badRequest().body(new ErrorView("CSV_READ_FAILED"));
        }
    }

    private ResponseEntity<?> upload(MultipartFile file, boolean confirm) {
        if (file.getSize() > ImportService.MAX_BYTES) return tooLarge();
        try {
            var ownerId = currentUser.id();
            var bytes = file.getBytes();
            return ResponseEntity.ok(confirm ? imports.confirm(ownerId, bytes) : imports.preview(ownerId, bytes));
        } catch (ImportService.ImportRejected rejected) {
            return ResponseEntity.unprocessableEntity().body(rejected.preview());
        } catch (DataIntegrityViolationException changed) {
            return ResponseEntity.status(409).body(new ErrorView("IMPORT_CONFLICT_RETRY_PREVIEW"));
        } catch (IOException invalid) {
            return ResponseEntity.badRequest().body(new ErrorView("CSV_READ_FAILED"));
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorView> tooLarge() {
        return ResponseEntity.status(413).body(new ErrorView("CSV_TOO_LARGE"));
    }

    public record ErrorView(String code) { }
}
