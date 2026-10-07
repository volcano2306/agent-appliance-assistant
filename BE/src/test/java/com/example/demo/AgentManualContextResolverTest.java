package com.example.demo;

import com.example.demo.dto.QARequest;
import com.example.demo.model.Manual;
import com.example.demo.model.MyManual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.MyManualRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.AgentManualContextResolver;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentManualContextResolverTest {
    private final ManualRepository manuals = mock(ManualRepository.class);
    private final MyManualRepository myManuals = mock(MyManualRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final AgentManualContextResolver resolver = new AgentManualContextResolver(manuals, myManuals, products);

    @Test
    void linkedMyManualUsesPlatformIdAndVerifiedMetadata() {
        Manual platform = Manual.builder().id("platform-1").brandName("奥克斯")
                .modelName("ASW-100HTB").productName("洗衣机说明书").build();
        MyManual mine = MyManual.builder().id("mine-1").manualId("platform-1")
                .ownerUserId("user-1").build();
        when(manuals.findById("platform-1")).thenReturn(Optional.of(platform));
        when(myManuals.findById("mine-1")).thenReturn(Optional.of(mine));
        QARequest request = new QARequest();
        request.setUserId("user-1");
        request.setManualId("platform-1");
        request.setManualRefId("mine-1");
        request.setManualName("伪造标题");

        AgentManualContextResolver.Target result = resolver.resolve(request);
        assertEquals("platform-1", result.platformId());
        assertEquals("mine-1", result.referenceId());
        assertEquals("奥克斯", result.brand());
        assertEquals("洗衣机说明书", result.name());
    }

    @Test
    void privateUploadHasNoPlatformIdAndCannotBeConfusedWithIt() {
        MyManual mine = MyManual.builder().id("upload-1").ownerUserId("user-1")
                .productName("私人上传说明书").build();
        when(myManuals.findById("upload-1")).thenReturn(Optional.of(mine));
        QARequest request = new QARequest();
        request.setUserId("user-1");
        request.setManualId("upload-1");
        request.setManualRefId("upload-1");

        AgentManualContextResolver.Target result = resolver.resolve(request);
        assertEquals("", result.platformId());
        assertEquals("upload-1", result.referenceId());
    }

    @Test
    void rejectsDifferentManualTargetsAndAnotherUsersPrivateUpload() {
        when(manuals.findById("platform-1")).thenReturn(Optional.of(Manual.builder().id("platform-1").build()));
        when(manuals.findById("platform-2")).thenReturn(Optional.of(Manual.builder().id("platform-2").build()));
        QARequest conflict = new QARequest();
        conflict.setManualId("platform-1");
        conflict.setManualRefId("platform-2");
        assertEquals("manual_context_conflict", assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(conflict)).getMessage());

        when(myManuals.findById("mine-2")).thenReturn(Optional.of(
                MyManual.builder().id("mine-2").ownerUserId("other-user").build()));
        QARequest other = new QARequest();
        other.setUserId("user-1");
        other.setManualId("mine-2");
        assertEquals("invalid_manual_context", assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(other)).getMessage());
    }

    @Test
    void rejectsProductThatDoesNotBelongToSelectedManual() {
        when(manuals.findById("platform-1")).thenReturn(Optional.of(
                Manual.builder().id("platform-1").productId("product-1").build()));
        QARequest request = new QARequest();
        request.setManualId("platform-1");
        request.setProductId("product-2");
        assertEquals("invalid_product_context", assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(request)).getMessage());
    }
}
