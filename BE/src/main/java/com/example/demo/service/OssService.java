package com.example.demo.service;

import java.io.InputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.extern.slf4j.Slf4j;

/**
 * 对象存储上传服务（基于 MinIO Java SDK，兼容任意 S3 协议存储）
 */
@Slf4j
@Service
public class OssService {

    private final MinioClient minioClient;

    @Value("${oss.bucket}")
    private String bucket;

    /**
     * 公网可访问的 URL 前缀，如 http://localhost:9000/sms-manuals
     */
    @Value("${oss.public-url}")
    private String publicUrl;

    public OssService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 上传文件到 OSS
     *
     * @param key 对象 key（路径），如 camera/Mamiya/cover.jpg
     * @param inputStream 文件输入流
     * @param size 字节数（-1 表示未知，触发分块上传）
     * @param contentType MIME 类型
     * @return 公网访问 URL
     */
    public String upload(String key, InputStream inputStream, long size, String contentType) {
        try {
            ensureBucketExists();
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(key)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build());
            String url = (publicUrl.endsWith("/") ? publicUrl : publicUrl + "/") + key;
            log.debug("OssService: 上传成功 {} -> {}", key, url);
            return url;
        } catch (Exception e) {
            throw new RuntimeException("OSS 上传失败: " + key, e);
        }
    }

    // ----------------------------------------------------------------
    /**
     * 若 Bucket 不存在则创建并设置公开读策略
     */
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":"
                    + "[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},"
                    + "\"Action\":[\"s3:GetObject\"],"
                    + "\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
            minioClient.setBucketPolicy(
                    SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
            log.info("OssService: 已创建 Bucket [{}] 并设置公开读", bucket);
        }
    }
}
