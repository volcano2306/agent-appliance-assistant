package com.example.demo.controller;

import com.example.demo.model.Product;
import com.example.demo.repository.ProductRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;

    @GetMapping("/search")
    public List<Product> search(@RequestParam("q") String q) {
        return productRepository.findByNameIgnoreCaseContainingOrModelIgnoreCaseContaining(q, q);
    }

    /**
     * 兼容两种请求：
     * 1) 纯字符串： "6901234567890"
     * 2) JSON 对象：{ "barcode": "6901234567890" }
     */
    @PostMapping("/scan")
    public Product scan(@RequestBody(required = false) Object body) {
        String barcode = null;

        if (body == null) return null;

        // 如果前端直接传字符串，会变成 LinkedHashMap? 取决于 content-type
        if (body instanceof String s) {
            barcode = normalizeBarcode(s);
        } else {
            // 尝试按 Map/对象读取（最常见：{barcode: "..."}）
            // 这里用简单方式：转成 JSON 字符串再解析成本地 DTO 的方式也可
            // 为避免引入额外 JSON 处理，这里用 toString 粗处理（demo用）
            String text = body.toString();
            // 兼容 "{barcode=xxx}" 这类 toString
            int idx = text.indexOf("barcode=");
            if (idx >= 0) {
                barcode = text.substring(idx + "barcode=".length()).replace("}", "").trim();
            }
        }

        if (barcode == null || barcode.isBlank()) return null;
        return productRepository.findByBarcodesContaining(barcode);
    }

    @PostMapping("/scan2")
    public Product scan2(@RequestBody BarcodeScanRequest req) {
        if (req == null || req.getBarcode() == null) return null;
        return productRepository.findByBarcodesContaining(normalizeBarcode(req.getBarcode()));
    }

    @GetMapping("/{id}")
    public Product getById(@PathVariable String id) {
        return productRepository.findById(id).orElse(null);
    }

    private String normalizeBarcode(String raw) {
        if (raw == null) return null;
        // 去掉 JSON 字符串可能带来的引号
        return raw.trim().replace("\"", "");
    }

    @Data
    public static class BarcodeScanRequest {
        private String barcode;
    }
}
