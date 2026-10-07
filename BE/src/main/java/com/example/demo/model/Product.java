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
@Document(collection = "products")
public class Product {
    @Id
    private String id;
    private String name;        // 产品名称
    private String model;       // 型号
    private String brandId;     // → brands._id
    private String categoryId;  // → categories._id
    private String imageUrl;    // 产品图片 URL
    private List<String> barcodes; // 条码（保留，支持扫码查询）
    private Instant createdAt;
}
