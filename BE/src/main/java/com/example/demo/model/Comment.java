package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "comments")
public class Comment {
    @Id
    private String id;
    private String targetType; // "manual" | "myManual" | "comment"
    private String targetId;
    private String fromUserId; // 发出者 → users._id
    private String toUserId;   // 收到者（通常是内容归属者）
    private String fromUserName;
    private String fromAvatar;
    private String content;
    private Instant createdAt;
}
