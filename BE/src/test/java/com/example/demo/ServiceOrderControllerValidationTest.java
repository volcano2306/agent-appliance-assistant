package com.example.demo;

import com.example.demo.controller.ServiceOrderController;
import com.example.demo.model.Manual;
import com.example.demo.repository.ManualRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.ServiceOrderRepository;
import com.example.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ServiceOrderControllerValidationTest {
    private final ServiceOrderRepository orders = mock(ServiceOrderRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ManualRepository manuals = mock(ManualRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ServiceOrderController controller = new ServiceOrderController(orders, users, manuals, products);

    private ServiceOrderController.CreateRequest request() {
        ServiceOrderController.CreateRequest request = new ServiceOrderController.CreateRequest();
        request.setUserId("user-1");
        request.setIssueDesc("不排水");
        request.setManualId("manual-1");
        return request;
    }

    @Test
    void rejectsMyManualIdInsteadOfSavingItAsPlatformId() {
        when(users.existsById("user-1")).thenReturn(true);
        assertEquals(400, controller.create(request(), "user-1").getStatusCode().value());
        verifyNoInteractions(orders);
    }

    @Test
    void rejectsBrandThatConflictsWithVerifiedManual() {
        when(users.existsById("user-1")).thenReturn(true);
        when(manuals.findById("manual-1")).thenReturn(Optional.of(
                Manual.builder().id("manual-1").brandName("奥克斯").build()));
        ServiceOrderController.CreateRequest request = request();
        request.setBrandName("编造品牌");
        assertEquals(400, controller.create(request, "user-1").getStatusCode().value());
        verifyNoInteractions(orders);
    }
}
