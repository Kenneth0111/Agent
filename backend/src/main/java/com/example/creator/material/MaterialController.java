package com.example.creator.material;

import com.example.creator.auth.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    private final CurrentUser currentUser;
    private final MaterialService materials;

    MaterialController(CurrentUser currentUser, MaterialService materials) {
        this.currentUser = currentUser;
        this.materials = materials;
    }

    @GetMapping
    public List<MaterialService.MaterialSummary> list() {
        return materials.ownedBy(currentUser.id());
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
            return ResponseEntity.status("MATERIAL_TOO_LARGE".equals(invalid.getMessage()) ? 413 : 400)
                    .body(new ErrorView(invalid.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return materials.delete(currentUser.id(), id)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record ErrorView(String code) { }
}
