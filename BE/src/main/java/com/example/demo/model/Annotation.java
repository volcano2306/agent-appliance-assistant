package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "annotations")
public class Annotation {
    @Id
    private String id;
    private String productId;
    private String view; // "front","top","side"
    private List<Hotspot> hotspots;
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Hotspot {
        private String id;
        private double x;
        private double y;
        private double w;
        private double h;
        private String title;
        private String desc;
        private Integer page;
    }
}

