package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_devices")
public class UserDevice {
    @Id
    private String id;
    private String userOpenId;
    private String productId;
    private String nickname;
    private List<String> manualIds;
    private Instant createdAt;
}
