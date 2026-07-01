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
import java.util.UUID;

@Slf4j
@Service
public class OssService {

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

        // 验证文件类型
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("只能上传图片文件");
        }

        // 验证文件大小（10MB）
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("图片大小不能超过10MB");
        }

        // 生成OSS对象key：folder/yyyy/MM/dd/uuid.ext
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String ext = getExtension(file.getOriginalFilename());
        String objectKey = folder + "/" + datePath + "/" + UUID.randomUUID() + ext;

        // 上传
        ossClient.putObject(bucketName, objectKey, file.getInputStream());
        log.info("OSS上传成功: {}", objectKey);

        return urlPrefix + objectKey;
    }

    private String getExtension(String filename) {
        if (filename == null) return ".jpg";
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex >= 0 ? filename.substring(dotIndex) : ".jpg";
    }
}
