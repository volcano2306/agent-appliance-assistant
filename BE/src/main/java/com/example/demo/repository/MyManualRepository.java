package com.example.demo.repository;

import com.example.demo.model.MyManual;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MyManualRepository extends MongoRepository<MyManual, String> {
    List<MyManual> findByOwnerUserId(String ownerUserId);
    List<MyManual> findByOwnerUserIdAndVisibility(String ownerUserId, String visibility);
    List<MyManual> findByVisibility(String visibility);
    List<MyManual> findByManualId(String manualId);
    boolean existsByOwnerUserIdAndManualId(String ownerUserId, String manualId);
}
