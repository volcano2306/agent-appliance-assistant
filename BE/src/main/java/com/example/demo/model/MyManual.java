package com.example.demo.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户收藏的说明书 — 对应 MongoDB my_manuals 集合
 * 通过 manualId 关联 manuals 集合中的平台说明书。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "my_manuals")
public class MyManual {

    @Id
    private String id;

    /** 归属用户 ID（关联 users._id） */
    private String ownerUserId;

    /** 关联的平台说明书 ID（关联 manuals._id） */
    private String manualId;

    /** 用户上传文件路径（仅 isFromUserUpload=true 时有值） */
    private String uploadedFilePath;
    private String uploadedFileName;
    private Long uploadedFileSize;

    /** 产品信息（用户可编辑） */
    private String productName;
    private String brandName;
    private String modelName;
    private String categoryCode;

    /** 可见性：private / public */
    @Builder.Default
    private String visibility = "private";

    /** 用户重命名/备注 */
    private String nickname;

    /** 自定义图片 */
    private String imageUrl;

    /** 是否为用户自行上传（false = 从平台收藏） */
    @Builder.Default
    private boolean isFromUserUpload = false;

    /** 首次公开成功后 +6 积分标记 */
    private boolean publicRewarded;

    private Instant createdAt;
    private Instant updatedAt;
}
