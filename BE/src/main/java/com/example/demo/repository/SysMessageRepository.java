package com.example.demo.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.SysMessage;

@Repository
public interface SysMessageRepository extends MongoRepository<SysMessage, String> {
    List<SysMessage> findByToUserIdOrderByCreatedAtDesc(String toUserId);
    long countByToUserIdAndReadAtIsNull(String toUserId);
}
