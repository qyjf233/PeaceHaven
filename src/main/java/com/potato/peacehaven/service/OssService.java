package com.potato.peacehaven.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class OssService {

    /** 允许的图片 MIME 类型白名单 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    /** 允许的文件扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".webp", ".gif"
    );

    /** 最大文件大小 5MB */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private final OSS ossClient;
    private final String bucketName;
    private final String urlPrefix;

    public OssService(
            @Value("${aliyun.access-key-id}") String accessKeyId,
            @Value("${aliyun.access-key-secret}") String accessKeySecret,
            @Value("${aliyun.oss.endpoint}") String endpoint,
            @Value("${aliyun.oss.bucket-name}") String bucketName,
            @Value("${aliyun.oss.url-prefix}") String urlPrefix
    ) {
        this.bucketName = bucketName;
        this.urlPrefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 上传图片到OSS
     * @param file 上传的文件
     * @param folder 存储目录（如 "building-contest"）
     * @return 完整的图片URL
     */
    public String uploadImage(MultipartFile file, String folder) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }

        // 验证文件类型（白名单）
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("仅支持 JPG/PNG/WebP/GIF 格式的图片");
        }

        // 验证文件大小
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("图片大小不能超过5MB");
        }

        // 生成OSS对象key：folder/yyyy/MM/dd/uuid.ext
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String ext = getSafeExtension(file.getOriginalFilename());
        String objectKey = folder + "/" + datePath + "/" + UUID.randomUUID() + ext;

        // 上传
        ossClient.putObject(bucketName, objectKey, file.getInputStream());
        log.info("OSS上传成功: {}", objectKey);

        return urlPrefix + objectKey;
    }

    /**
     * 提取并验证文件扩展名（白名单校验，防止伪装扩展名攻击）
     */
    private String getSafeExtension(String filename) {
        if (filename == null) return ".jpg";
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return ".jpg";
        String ext = filename.substring(dotIndex).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件扩展名: " + ext);
        }
        return ext;
    }
}
