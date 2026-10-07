package com.example.demo.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台说明书（公共资源库） 所有用户可见、可搜索、可添加到"我的说明书"
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "manuals")
public class Manual {

    @Id
    private String id;

    // 产品关联信息
    private String productId;       // → products._id
    private String productName;     // 产品名称（冗余，方便展示）
    private String brandName;       // 品牌名称（冗余）
    private String modelName;       // 型号（冗余）
    private String categoryCode;    // 分类代码（冗余）

    // 文件 & 展示信息
    private String imageUrl;        // 产品封面图 API URL，如 /api/files/images/{folder}/{file}
    private String pdfUrl;          // PDF API URL，如 /api/files/pdfs/{folder}/{file}
    private String filename;        // 原始文件名
    private String filePath;        // 文件本地存储路径
    private Long fileSize;          // 文件大小（字节）
    private String summary;         // 产品简介
    private String manualContent;   // PDF 解析后的文本内容（用于搜索/AI）

    // 上传者信息
    private String uploaderId;      // 上传用户ID
    private Instant uploadedAt;     // 上传时间

    // 解析状态
    private boolean isParsing;      // 是否正在解析
    private boolean isParsed;       // 是否已解析
    private Integer pages;          // 页数
    private List<String> pageImages; // PDF 每页渲染为图片后的 OSS URL 列表
    private Instant parseStartedAt;
    private Instant parseFinishedAt;
    private String parseError;

    // 统计信息
    private Integer viewCount;      // 浏览次数
    private Integer addedCount;     // 被添加到"我的说明书"次数

    private Instant createdAt;
    private Instant updatedAt;
}
