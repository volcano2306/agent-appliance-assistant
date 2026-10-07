package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "brand_messages")
public class BrandMessage {
    @Id
    private String id;
    private String brandId;
    private String brandName;
    private String toUserId;
    private String content;
    private Instant createdAt;
    private Instant readAt;
}
