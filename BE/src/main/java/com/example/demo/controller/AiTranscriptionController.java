package com.example.demo.controller;

import com.example.demo.repository.UserRepository;
import com.example.demo.service.PythonAgentClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/transcriptions")
@RequiredArgsConstructor
public class AiTranscriptionController {

    private final PythonAgentClient pythonAgentClient;
    private final UserRepository userRepository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcribe(@RequestPart("audio") MultipartFile audio,
                                        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (userId == null || userId.isBlank() || !userRepository.existsById(userId.trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid_user"));
        }
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "audio_required"));
        }
        if (!pythonAgentClient.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "python_agent_disabled"));
        }
        try {
            return ResponseEntity.ok(pythonAgentClient.transcribe(audio));
        } catch (Exception ex) {
            return ResponseEntity.status(502).body(Map.of("error", "asr_failed", "message", ex.getMessage()));
        }
    }
}

