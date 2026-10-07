package com.example.demo.controller;

import com.example.demo.model.Annotation;
import com.example.demo.repository.AnnotationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/annotations")
@RequiredArgsConstructor
public class AnnotationController {

    private final AnnotationRepository annotationRepository;

    @GetMapping
    public List<Annotation> getByProduct(@RequestParam String productId,
                                         @RequestParam(required = false) String view) {
        String v = (view == null || view.isBlank()) ? "front" : view;
        return annotationRepository.findByProductIdAndView(productId, v);
    }

    @PostMapping
    public Annotation create(@RequestBody Annotation annotation) {
        // id 由 MongoDB 生成即可
        return annotationRepository.save(annotation);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Annotation> update(@PathVariable String id, @RequestBody Annotation annotation) {
        return annotationRepository.findById(id).map(existing -> {
            // 保持 ID 不变
            annotation.setId(existing.getId());
            Annotation saved = annotationRepository.save(annotation);
            return ResponseEntity.ok(saved);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!annotationRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        annotationRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
