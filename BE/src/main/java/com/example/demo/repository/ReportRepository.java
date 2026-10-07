package com.example.demo.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.Report;

@Repository
public interface ReportRepository extends MongoRepository<Report, String> {
    List<Report> findByReporterUserId(String reporterUserId);
    List<Report> findByStatus(String status);
    List<Report> findByTargetTypeAndTargetId(String targetType, String targetId);
}
