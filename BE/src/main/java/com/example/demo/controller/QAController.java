package com.example.demo.controller;

import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.example.demo.service.QAService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qa")
@RequiredArgsConstructor
public class QAController {

    private final QAService qaService;

    @PostMapping
    public ResponseEntity<QAResponse> ask(@RequestBody QARequest req) {
        QAResponse resp = qaService.answer(req);
        return ResponseEntity.ok(resp);
    }
}
