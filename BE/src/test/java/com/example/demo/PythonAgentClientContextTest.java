package com.example.demo;

import com.example.demo.dto.QARequest;
import com.example.demo.service.AgentManualContextResolver;
import com.example.demo.service.PythonAgentClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class PythonAgentClientContextTest {
    @Test
    void savesCanonicalManualFieldsInsteadOfUnverifiedClientLabels() {
        AgentManualContextResolver resolver = mock(AgentManualContextResolver.class);
        QARequest request = new QARequest();
        request.setUserId("user-1");
        request.setManualId("mine-1");
        request.setManualRefId("mine-1");
        request.setManualName("客户端填写的标题");
        when(resolver.resolve(request)).thenReturn(new AgentManualContextResolver.Target(
                "platform-1", "mine-1", "已核实的标题", "洗衣机", "奥克斯", "ASW-100HTB", ""));
        PythonAgentClient client = new PythonAgentClient("http://localhost:8001", "", true, false, 10, resolver);

        client.normalizeRequestContext(request);

        assertEquals("platform-1", request.getManualId());
        assertEquals("mine-1", request.getManualRefId());
        assertEquals("已核实的标题", request.getManualName());
    }
}
