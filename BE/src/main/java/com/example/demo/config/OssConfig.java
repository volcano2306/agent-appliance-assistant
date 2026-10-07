package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.MinioClient;

/**
 * 对象存储客户端配置（S3 兼容） 默认连接本地 MinIO，修改 oss.* 属性即可切换至阿里云 OSS / 腾讯云 COS / AWS S3。
 *
 * MinIO 快速启动（Docker）： docker run -d -p 9000:9000 -p 9001:9001 \ -e
 * MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \ minio/minio
 * server /data --console-address ":9001" 管理界面：http://localhost:9001
 */
@Configuration
public class OssConfig {

    @Value("${oss.endpoint}")
    private String endpoint;

    @Value("${oss.access-key}")
    private String accessKey;

    @Value("${oss.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
