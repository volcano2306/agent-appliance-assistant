package com.example.demo.controller;

import com.example.demo.service.AiTurnLedgerService;
import com.example.demo.service.SessionMemoryFault;
import com.example.demo.dto.SessionMemory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/internal/agent/execution")
public class AgentExecutionController {
    private final AiTurnLedgerService ledger;
    private final String token;
    public AgentExecutionController(AiTurnLedgerService ledger,
            @Value("${agent.python.internal-token:}") String token) {
        this.ledger = ledger; this.token = token == null ? "" : token;
    }
    public record Check(String userId, String conversationId, String requestId, long executionVersion) { }
    private boolean authorized(String supplied) {
        return !token.isBlank() && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    @PostMapping("/check")
    public ResponseEntity<?> check(@RequestHeader(value="X-Internal-Token", defaultValue="") String supplied,
                                   @RequestBody Check input) {
        if (!authorized(supplied))
            return ResponseEntity.status(401).body(Map.of("error", "invalid_internal_token"));
        return ResponseEntity.ok(Map.of("allowed", ledger.check(input.userId(), input.conversationId(),
                input.requestId(), input.executionVersion())));
    }
    public record Terminal(Check execution, SessionMemory.PythonResponse response) { }
    @PostMapping("/terminal")
    public ResponseEntity<?> terminal(@RequestHeader(value="X-Internal-Token", defaultValue="") String supplied,
                                     @RequestBody Terminal input) {
        if (!authorized(supplied)) return ResponseEntity.status(401).body(Map.of("error", "invalid_internal_token"));
        try {
            Check e = input.execution();
            ledger.acceptTerminal(e.userId(), e.conversationId(), e.requestId(), e.executionVersion(), input.response());
            return ResponseEntity.ok(Map.of("accepted", true));
        } catch (SessionMemoryFault ex) { return ResponseEntity.status(409).body(Map.of("error", ex.getMessage())); }
    }
    public record Recovery(Check execution, boolean reviewedUnknownOutcome) { }
    @PostMapping("/recover-unknown")
    public ResponseEntity<?> recover(@RequestHeader(value="X-Internal-Token", defaultValue="") String supplied,
                                    @RequestBody Recovery input) {
        if (!authorized(supplied)) return ResponseEntity.status(401).body(Map.of("error", "invalid_internal_token"));
        try {
            Check e = input.execution();
            ledger.closeUnknown(e.userId(), e.conversationId(), e.requestId(), e.executionVersion(), input.reviewedUnknownOutcome());
            return ResponseEntity.ok(Map.of("accepted", true));
        } catch (SessionMemoryFault ex) { return ResponseEntity.status(409).body(Map.of("error", ex.getMessage())); }
    }
}
