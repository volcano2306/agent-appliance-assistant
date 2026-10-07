package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stranger_messages")
public class StrangerMessage {
    @Id
    private String id;
    private String fromUserId;    // 可选（允许匿名）
    private String fromNickname;  // 匿名时存昵称
    private String toUserId;
    private String content;
    private Instant createdAt;
    private Instant readAt;
}
