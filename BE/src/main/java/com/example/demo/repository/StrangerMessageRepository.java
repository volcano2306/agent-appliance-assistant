package com.example.demo.repository;

import com.example.demo.model.StrangerMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StrangerMessageRepository extends MongoRepository<StrangerMessage, String> {
    List<StrangerMessage> findByToUserIdOrderByCreatedAtDesc(String toUserId);
    List<StrangerMessage> findByFromUserIdOrderByCreatedAtDesc(String fromUserId);
    long countByToUserIdAndReadAtIsNull(String toUserId);
}
