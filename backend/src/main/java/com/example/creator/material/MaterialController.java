package com.example.creator.material;

import com.example.creator.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    private final CurrentUser currentUser;
    private final MaterialService materials;
    private final MaterialSearchService search;

    MaterialController(CurrentUser currentUser, MaterialService materials, MaterialSearchService search) {
        this.currentUser = currentUser;
        this.materials = materials;
        this.search = search;
    }

    @GetMapping
    public List<MaterialService.MaterialSummary> list() {
        return materials.ownedBy(currentUser.id());
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required = false) String accountId,
                                    @RequestParam(required = false) String q) {
        try {
            return ResponseEntity.ok(search.search(currentUser.id(), accountId, q));
        } catch (MaterialSearchService.SearchFailure failure) {
            return ResponseEntity.status("ACCOUNT_NOT_FOUND".equals(failure.getMessage()) ? 404 : 400)
                    .body(new ErrorView(failure.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaterialService.MaterialDetail> get(@PathVariable String id) {
        return ResponseEntity.of(materials.find(currentUser.id(), id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) MaterialService.MaterialInput input) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(materials.create(currentUser.id(), input));
        } catch (TextExtractor.MaterialInvalid invalid) {
            return invalid(invalid);
        }
    }

    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> createPdf(@RequestParam String title, @RequestParam String purpose,
                                       @RequestParam(required = false) String sourceUrl,
                                       @RequestParam(required = false) List<String> accountIds,
                                       @RequestParam MultipartFile file) {
        try {
            var input = new MaterialService.MaterialInput(title, purpose, sourceUrl, null, null, "PDF", accountIds);
            return ResponseEntity.status(HttpStatus.CREATED).body(materials.createPdf(currentUser.id(), input, file));
        } catch (TextExtractor.MaterialInvalid invalid) {
            return invalid(invalid);
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorView> tooLarge() {
        return ResponseEntity.status(413).body(new ErrorView("MATERIAL_TOO_LARGE"));
    }

    private ResponseEntity<ErrorView> invalid(TextExtractor.MaterialInvalid invalid) {
        var code = invalid.getMessage();
        return ResponseEntity.status("MATERIAL_TOO_LARGE".equals(code) ? 413
                : "ACCOUNT_NOT_FOUND".equals(code) ? 404 : 400).body(new ErrorView(code));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return materials.delete(currentUser.id(), id)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record ErrorView(String code) { }
}
