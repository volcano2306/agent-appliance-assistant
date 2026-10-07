package com.example.demo.repository;

import com.example.demo.model.AiConversation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface AiConversationRepository extends MongoRepository<AiConversation, String> {
    List<AiConversation> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<AiConversation> findByIdAndUserId(String id, String userId);
}
