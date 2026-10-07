package com.example.demo.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.Brand;

@Repository
public interface BrandRepository extends MongoRepository<Brand, String> {
    Optional<Brand> findByName(String name);
    Optional<Brand> findByNormalizedName(String normalizedName);
}
