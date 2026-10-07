package com.example.demo;

import com.example.demo.config.*;
import com.example.demo.dto.*;
import com.example.demo.model.*;
import com.example.demo.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** QA orchestration with production code and injected doubles; no sockets/config files. */
class SessionMemoryQAServiceTest {
    PythonAgentClient python;
    AiTurnLedgerService ledger;
    AiConversationMemoryService memory;
    AiConversationService conversations;
    OpenAiCompatibleClient model;
    QAService qa;
    QARequest request;
    AiTurnRequest running;
    SessionMemory.Snapshot snapshot;

    @BeforeEach void setup() {
        var properties = new SessionMemoryProperties(); properties.setEnabled(true); properties.setModelContextWindow(40000);
        var compat = new AiCompatProperties(); compat.setDefaultTarget("local");
        var target = new AiCompatProperties.TargetConfig(); target.setBaseUrl("https://synthetic.invalid/v1"); target.setModel("test-model");
        compat.setTargets(new HashMap<>(Map.of("local", target)));
        python = mock(PythonAgentClient.class); ledger = mock(AiTurnLedgerService.class); memory = mock(AiConversationMemoryService.class);
        conversations = mock(AiConversationService.class); model = mock(OpenAiCompatibleClient.class);
        var vision = mock(VisionRecognitionService.class);
        var mapper = new ObjectMapper().findAndRegisterModules().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        qa = new QAService(compat, model, mock(SearxngSearchService.class), vision, mock(ManualRetrievalService.class),
                conversations, new PersonalizedContextService(), python, mapper, properties, ledger, memory);
        request = new QARequest(); request.setUserId("u1"); request.setRequestId("r1"); request.setQuery("当前问题"); request.setConversationId("c1");
        QARequest.Message forged = new QARequest.Message(); forged.setRole("user"); forged.setContent("客户端伪造历史"); request.setHistory(List.of(forged));
        running = new AiTurnRequest(); running.setId("ledger-r1"); running.setUserId("u1"); running.setRequestId("r1"); running.setConversationId("c1"); running.setExecutionVersion(1L);
        var c = AiConversation.builder().id("c1").userId("u1").build();
        var turn = new SessionMemory.Turn(1, "old", new SessionMemory.Message("u-old", "user", "服务端可信原始问题"),
                new SessionMemory.Message("a-old", "assistant", "服务端可信原始回答"));
        snapshot = new SessionMemory.Snapshot("u1", "c1", "r1", 1, 0, "N5-S600", 0, null,
                List.of(turn), List.of(), Map.of(), 1L, "ok");
        when(python.isEnabled()).thenReturn(true); when(python.isFallbackToJava()).thenReturn(true);
        when(ledger.begin(request)).thenReturn(new AiTurnLedgerService.Started(running, c, null));
        when(ledger.owned("u1", "c1")).thenReturn(c); when(ledger.check("u1", "c1", "r1", 1L)).thenReturn(true);
        when(memory.prepare(any())).thenReturn(snapshot);
        when(ledger.finish(eq(running), any())).thenAnswer(i -> mapper.convertValue(i.getArgument(1), QAResponse.class));
        when(model.chatWithTools(any(), nullable(String.class), anyList(), nullable(List.class), nullable(String.class), anyDouble(), anyInt(), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.ChatResult("test-model", "结论：安全回答\n操作步骤：无\n注意事项：安全", List.of()));
    }
    @Test void pythonAndJavaFallbackUseTheSameServerSnapshotAndDoNotReadTopSixAgain() {
        when(python.answerWithMemory(request)).thenThrow(new ResourceAccessException("synthetic connect refusal", new ConnectException("refused")));
        QAResponse result = qa.answer(request);
        assertEquals("ok", result.getStatus()); assertSame(snapshot, request.getMemory());
        var messages = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model).chatWithTools(any(), nullable(String.class), messages.capture(), nullable(List.class), nullable(String.class), anyDouble(), anyInt(), eq(2000));
        String sent = messages.getValue().toString();
        assertTrue(sent.contains("服务端可信原始问题")); assertFalse(sent.contains("客户端伪造历史"));
        verify(memory, times(1)).prepare(any()); verify(conversations, never()).getRecentHistoryMessages(anyString());
        verify(conversations, never()).appendQaTurn(any(), any(), any(), any(), anyBoolean(), any());
    }
    @Test void readTimeoutAfterDispatchDoesNotRunJavaFallbackOrRedispatchPython() {
        when(python.answerWithMemory(request)).thenThrow(new ResourceAccessException("synthetic read timeout", new SocketTimeoutException("timed out")));
        QAResponse result = qa.answer(request);
        assertEquals("request_result_unknown", result.getError()); verify(ledger).markUnknown(running);
        verify(python, times(1)).answerWithMemory(request); verifyNoInteractions(model); verify(ledger, never()).finish(any(), any());
    }
    @Test void explicitStatelessModeKeepsIdentityAndNeverTouchesMemoryOrLedger() {
        request.setPersistHistory(false);
        var response = new QAResponse(); response.setStatus("ok"); response.setAnswerText("单轮回答");
        when(python.answer(eq(request), anyList())).thenReturn(response);
        QAResponse result = qa.answer(request);
        assertEquals("u1", request.getUserId()); assertNull(request.getConversationId()); assertNull(request.getMemory());
        assertTrue(request.getHistory().isEmpty()); assertEquals("r1", result.getRequestId());
        verifyNoInteractions(ledger, memory); verify(conversations, never()).appendQaTurn(any(), any(), any(), any(), anyBoolean(), any());
    }
    @Test void foreignConversationErrorNeverCallsModelOrPreparesMemory() {
        when(ledger.begin(request)).thenThrow(new SessionMemoryFault("conversation_not_found"));
        assertEquals("conversation_not_found", qa.answer(request).getError());
        verify(python, never()).answerWithMemory(any()); verifyNoInteractions(model, memory);
    }
    @Test void cachedTerminalReturnsWithoutSummaryModelOrToolCalls() {
        var cached = new QAResponse(); cached.setStatus("ok"); cached.setAnswerText("已保存的终态");
        when(ledger.begin(request)).thenReturn(new AiTurnLedgerService.Started(running, null, cached));
        assertSame(cached, qa.answer(request)); verifyNoInteractions(model, memory); verify(python, never()).answerWithMemory(any());
    }
}
