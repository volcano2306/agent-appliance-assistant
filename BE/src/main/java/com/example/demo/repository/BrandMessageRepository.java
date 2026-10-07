package com.example.demo.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.BrandMessage;

@Repository
public interface BrandMessageRepository extends MongoRepository<BrandMessage, String> {
    List<BrandMessage> findByToUserIdOrderByCreatedAtDesc(String toUserId);
    long countByToUserIdAndReadAtIsNull(String toUserId);
}
