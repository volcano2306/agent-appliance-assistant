package com.example.demo.repository;

import com.example.demo.model.ServiceOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ServiceOrderRepository extends MongoRepository<ServiceOrder, String> {
    List<ServiceOrder> findByUserIdOrderByCreatedAtDesc(String userId);
    Optional<ServiceOrder> findByIdAndUserId(String id, String userId);
}

