package com.example.demo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.example.demo.config.SessionMemoryProperties;
import com.example.demo.dto.QARequest;
import com.example.demo.dto.QAResponse;
import com.example.demo.dto.SessionMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionMemoryContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sharedPythonFixtureRoundTrips() throws Exception {
        Path fixture = Path.of(System.getProperty("basedir", "."))
            .resolve("../agent/tests/fixtures/session_memory/contract.json").normalize();
        var original = mapper.readTree(fixture.toFile());
        var snapshot = mapper.treeToValue(original, SessionMemory.Snapshot.class);
        assertEquals(original, mapper.readTree(mapper.writeValueAsBytes(snapshot)));
        assertEquals("ai", snapshot.recentTurns().get(0).assistant().role());
    }

    @Test void publicRequestCannotInjectInternalSnapshot() throws Exception {
        QARequest request = mapper.readValue("{\"query\":\"x\",\"memory\":{\"userId\":\"other\"}}", QARequest.class);
        assertNull(request.getMemory());
        assertNull(request.getPersistHistory());
        assertFalse(mapper.valueToTree(request).has("memory"));
    }

    @Test void publicResponseHasNoInternalReceipts() {
        QAResponse response = new QAResponse();
        response.setRequestId("r1");
        assertFalse(mapper.valueToTree(response).has("observedWriteOutcomes"));
        assertFalse(mapper.valueToTree(response).has("usageBreakdown"));
    }

    @Test void internalResponseDeserializesReceiptsSeparately() throws Exception {
        var internal = mapper.readValue("{\"status\":\"ok\",\"observedWriteOutcomes\":[{\"name\":\"create_repair_order\",\"writeStatus\":\"unknown\",\"target\":\"t\",\"identifier\":\"\",\"requestId\":\"r\"}],\"usageBreakdown\":[]}", SessionMemory.PythonResponse.class);
        assertEquals("unknown", internal.getObservedWriteOutcomes().get(0).writeStatus());
    }

    @Test void configurationIsDisabledUntilValidated() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var config = new SessionMemoryProperties();
            assertFalse(config.isEnabled());
            assertTrue(validator.validate(config).isEmpty());
            config.setEnabled(true);
            assertFalse(validator.validate(config).isEmpty());
            config.setModelContextWindow(32768);
            assertTrue(validator.validate(config).isEmpty());
            config.setRecentTurns(4);
            assertFalse(validator.validate(config).isEmpty());
        }
    }
}
