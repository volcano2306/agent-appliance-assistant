package com.example.demo.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据库初始化器。
 * 当 app.db.init=true 时，建立索引（不清空旧数据）。
 * 首次运行完成后，将 application.properties 中的 app.db.init 改为 false 或删除该行。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class DatabaseInitializer implements CommandLineRunner {

    private final MongoTemplate mongoTemplate;

    @Value("${app.db.init:false}")
    private boolean dbInit;

    /** 需要建立索引的集合列表 */
    private static final List<String> COLLECTIONS = List.of(
            "products", "manuals", "brands", "categories",
            "users", "my_manuals",
            "comments", "likes", "favorites",
            "sys_messages", "stranger_messages", "brand_messages",
            "reports", "fragments", "annotations", "user_devices",
            "ai_conversations", "ai_messages", "service_orders"
    );

    @Override
    public void run(String... args) {
        if (!dbInit) {
            log.info("[DB] app.db.init=false，跳过索引初始化。");
            return;
        }
        log.warn("[DB] ===== 开始建立数据库索引（保留现有数据） =====");

        // 1. 确保集合存在（不删除数据）
        for (String col : COLLECTIONS) {
            if (!mongoTemplate.collectionExists(col)) {
                mongoTemplate.createCollection(col);
                log.info("[DB] 已创建集合: {}", col);
            } else {
                log.info("[DB] 集合已存在，保留数据: {}", col);
            }
        }

        // 2. 建索引
        // products
        var prodIdx = mongoTemplate.indexOps("products");
        prodIdx.ensureIndex(new Index().on("brandId", Sort.Direction.ASC));
        prodIdx.ensureIndex(new Index().on("categoryId", Sort.Direction.ASC));
        prodIdx.ensureIndex(new Index().on("barcodes", Sort.Direction.ASC));

        // manuals
        var manualIdx = mongoTemplate.indexOps("manuals");
        manualIdx.ensureIndex(new Index().on("productId", Sort.Direction.ASC));
        manualIdx.ensureIndex(new Index().on("brandId", Sort.Direction.ASC));

        // users（openid 唯一索引）
        var userIdx = mongoTemplate.indexOps("users");
        userIdx.ensureIndex(new Index().on("openid", Sort.Direction.ASC).unique());

        // brands
        var brandIdx = mongoTemplate.indexOps("brands");
        brandIdx.ensureIndex(new Index().on("normalizedName", Sort.Direction.ASC).unique().sparse());

        // my_manuals
        var myManualIdx = mongoTemplate.indexOps("my_manuals");
        myManualIdx.ensureIndex(new Index().on("ownerUserId", Sort.Direction.ASC));
        myManualIdx.ensureIndex(new Index().on("visibility", Sort.Direction.ASC));

        // comments / likes / favorites（多态目标查询）
        for (String col : List.of("comments", "likes", "favorites")) {
            var idx = mongoTemplate.indexOps(col);
            idx.ensureIndex(new Index().on("targetType", Sort.Direction.ASC).on("targetId", Sort.Direction.ASC));
            idx.ensureIndex(new Index().on("fromUserId", Sort.Direction.ASC));
            idx.ensureIndex(new Index().on("toUserId", Sort.Direction.ASC));
        }

        // sys_messages / stranger_messages / brand_messages（按收件人查）
        for (String col : List.of("sys_messages", "stranger_messages", "brand_messages")) {
            mongoTemplate.indexOps(col)
                    .ensureIndex(new Index().on("toUserId", Sort.Direction.ASC));
        }

        // reports
        mongoTemplate.indexOps("reports")
                .ensureIndex(new Index().on("status", Sort.Direction.ASC));

        // AI conversations / messages
        var aiConversationIdx = mongoTemplate.indexOps("ai_conversations");
        aiConversationIdx.ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("updatedAt", Sort.Direction.DESC));
        aiConversationIdx.ensureIndex(new Index().on("manualRefId", Sort.Direction.ASC));
        aiConversationIdx.ensureIndex(new Index().on("manualId", Sort.Direction.ASC));

        var aiMessageIdx = mongoTemplate.indexOps("ai_messages");
        aiMessageIdx.ensureIndex(new Index().on("conversationId", Sort.Direction.ASC).on("createdAt", Sort.Direction.ASC));

        var fragmentIdx = mongoTemplate.indexOps("fragments");
        fragmentIdx.ensureIndex(new Index().on("manualId", Sort.Direction.ASC).on("page", Sort.Direction.ASC).on("chunkIndex", Sort.Direction.ASC));

        var serviceOrderIdx = mongoTemplate.indexOps("service_orders");
        serviceOrderIdx.ensureIndex(new Index().on("userId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
        serviceOrderIdx.ensureIndex(new Index().on("status", Sort.Direction.ASC));

        log.warn("[DB] ===== 数据库索引建立完成，请将 app.db.init 改为 false =====");
    }
}
