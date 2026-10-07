package com.example.demo.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.Product;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {
    // 按产品名称 或 型号 模糊搜索
    List<Product> findByNameIgnoreCaseContainingOrModelIgnoreCaseContaining(String name, String model);
    // 按条形码精确查询（扫码用）
    Product findByBarcodesContaining(String barcode);
    // 按品牌/分类查询
    List<Product> findByBrandId(String brandId);
    List<Product> findByCategoryId(String categoryId);
}
