package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "favorites")
public class Favorite {
    @Id
    private String id;
    private String targetType; // "manual" | "myManual" | "comment"
    private String targetId;
    private String fromUserId;
    private String toUserId;
    private Instant createdAt;
}
