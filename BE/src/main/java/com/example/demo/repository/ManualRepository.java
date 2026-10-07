package com.example.demo.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.Manual;

@Repository
public interface ManualRepository extends MongoRepository<Manual, String> {

    List<Manual> findByCategoryCode(String categoryCode);

    List<Manual> findByBrandName(String brandName);

    List<Manual> findByCategoryCodeAndBrandName(String categoryCode, String brandName);

    List<Manual> findByUploaderId(String uploaderId);
}
