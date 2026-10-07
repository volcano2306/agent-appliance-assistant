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
@Document(collection = "brands")
public class Brand {
    @Id
    private String id;
    private String name;
    private String normalizedName;
    private String logoFilename;
    private String logoUrl;
    private String categoryCode;
    private List<String> aliases;
    private Instant createdAt;
    private Instant updatedAt;
}
