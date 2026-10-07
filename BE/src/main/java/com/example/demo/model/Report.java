package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "reports")
public class Report {
    @Id
    private String id;
    private String reporterUserId;
    private String targetType;  // "manual" | "myManual" | "comment" | "other"
    private String targetId;
    private String content;
    private List<String> images;
    private Instant createdAt;
    private String status;      // "open" | "processing" | "done"
}
