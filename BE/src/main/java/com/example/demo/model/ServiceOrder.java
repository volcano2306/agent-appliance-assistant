package com.example.demo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "service_orders")
public class ServiceOrder {
    @Id
    private String id;
    private String userId;
    private String deviceId;
    private String manualId;
    private String serviceType;
    private String deviceName;
    private String brandName;
    private String modelName;
    private String faultCode;
    private String issueDesc;
    private String contactPhone;
    private String preferredTime;
    private String conversationId;
    private String status;
    private String statusText;
    private Instant createdAt;
    private Instant updatedAt;
}

