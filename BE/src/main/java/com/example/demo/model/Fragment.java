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
@Document(collection = "fragments")
public class Fragment {
    @Id
    private String id;
    private String manualId;
    private String productId;
    private Integer page;
    private Integer chunkIndex;
    private String text;
    private String source; // "pdf_text" / "ocr"
    private List<Double> embedding;
    private String embeddingModel;
    private Instant createdAt;
}

