package com.example.demo;

import com.example.demo.controller.AgentExecutionController;
import com.example.demo.dto.SessionMemory;
import com.example.demo.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionMemoryExecutionControllerTest {
    @Test void emptyConfiguredTokenRejectsEvenEmptySuppliedToken() {
        var ledger = mock(AiTurnLedgerService.class); var controller = new AgentExecutionController(ledger, "");
        assertEquals(401, controller.check("", new AgentExecutionController.Check("u", "c", "r", 1)).getStatusCode().value());
        verifyNoInteractions(ledger);
    }
    @Test void wrongTokenCannotCheckDispatchOrSubmitTerminalOrRecover() {
        var ledger = mock(AiTurnLedgerService.class); var controller = new AgentExecutionController(ledger, "synthetic-token");
        var identity = new AgentExecutionController.Check("u", "c", "r", 1);
        assertEquals(401, controller.check("wrong", identity).getStatusCode().value());
        assertEquals(401, controller.terminal("wrong", new AgentExecutionController.Terminal(identity, new SessionMemory.PythonResponse())).getStatusCode().value());
        assertEquals(401, controller.recover("wrong", new AgentExecutionController.Recovery(identity, true)).getStatusCode().value());
        verifyNoInteractions(ledger);
    }
    @Test void correctTokenStillRequiresTheLedgerFenceAndExplicitRecovery() {
        var ledger = mock(AiTurnLedgerService.class); var controller = new AgentExecutionController(ledger, "synthetic-token");
        var identity = new AgentExecutionController.Check("u", "c", "r", 1);
        when(ledger.check("u", "c", "r", 1)).thenReturn(false);
        assertEquals(java.util.Map.of("allowed", false), controller.check("synthetic-token", identity).getBody());
        doThrow(new SessionMemoryFault("explicit_recovery_required")).when(ledger).closeUnknown("u", "c", "r", 1, false);
        assertEquals(409, controller.recover("synthetic-token", new AgentExecutionController.Recovery(identity, false)).getStatusCode().value());
    }
}
