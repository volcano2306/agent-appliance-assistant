package com.example.demo.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    private String id;

    /** 微信 openid，唯一 */
    @Indexed(unique = true)
    private String openid;

    /** 绑定的手机号 */
    private String phone;

    /** 加入时间，前端按当前时间计算使用天数 */
    private Instant joinAt;

    /** 用户积分 */
    @Builder.Default
    private int points = 0;

    private Instant createdAt;
    private Instant updatedAt;
}
