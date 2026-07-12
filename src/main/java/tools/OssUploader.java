package tools;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * OSS 传输加速上传工具
 * 用于海外环境快速上传打包好的 JAR 文件到阿里云 OSS
 *
 * 用法: mvn exec:java -Dexec.mainClass="tools.OssUploader"
 * 环境变量: ALIYUN_AK_ID, ALIYUN_AK_SECRET
 */
public class OssUploader {

    // === OSS 传输加速配置 ===
    private static final String ENDPOINT = "https://oss-accelerate.aliyuncs.com";
    private static final String BUCKET = "peacehaven";

    private static final String PROJECT_NAME = "peacehaven";
    private static final String DEPLOY_PREFIX = "peacehaven_jar/";

    public static void main(String[] args) {
        // 1. 读取环境变量
        String akId = System.getenv("ALIYUN_AK_ID");
        String akSecret = System.getenv("ALIYUN_AK_SECRET");
        if (akId == null || akId.isEmpty() || akSecret == null || akSecret.isEmpty()) {
            System.err.println("[ERROR] 环境变量 ALIYUN_AK_ID 和 ALIYUN_AK_SECRET 未设置");
            System.exit(1);
        }

        // 2. 查找 JAR 文件
        String jarPath = findJarFile();
        if (jarPath == null) {
            System.err.println("[ERROR] 未找到 target/ 下的 JAR 文件，请先执行 mvnw package");
            System.exit(1);
        }

        File jarFile = new File(jarPath);
        String version = extractVersion(jarFile.getName());
        String objectKey = DEPLOY_PREFIX + PROJECT_NAME + "-" + version + ".jar";

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   PeaceHaven OSS 传输加速上传工具       ║");
        System.out.println("╠══════════════════════════════════════════╣");
        System.out.println("║ 文件: " + jarFile.getName());
        System.out.println("║ 大小: " + formatSize(jarFile.length()));
        System.out.println("║ 目标: " + BUCKET + "/" + objectKey);
        System.out.println("║ 节点: 传输加速 (oss-accelerate)");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();

        // 3. 上传
        long startTime = System.currentTimeMillis();
        OSS ossClient = new OSSClientBuilder().build(ENDPOINT, akId, akSecret);
        try {
            System.out.print("上传中... ");
            PutObjectResult result = ossClient.putObject(new PutObjectRequest(BUCKET, objectKey, jarFile));
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;

            System.out.println("完成! (" + elapsed + "s)");
            System.out.println();
            System.out.println("✓ 上传成功");
            System.out.println("  ETag: " + result.getETag());
            System.out.println("  下载: https://" + BUCKET + ".oss-accelerate.aliyuncs.com/" + objectKey);
        } catch (Exception e) {
            System.err.println("✗ 上传失败: " + e.getMessage());
            System.exit(1);
        } finally {
            ossClient.shutdown();
        }
    }

    /**
     * 在 target/ 下查找 peacehaven-*.jar（排除 sources/javadoc）
     */
    private static String findJarFile() {
        File targetDir = new File("target");
        if (!targetDir.exists()) return null;

        File[] files = targetDir.listFiles((dir, name) ->
                name.startsWith(PROJECT_NAME)
                && name.endsWith(".jar")
                && !name.contains("sources")
                && !name.contains("javadoc")
                && !name.contains("original")
        );

        if (files == null || files.length == 0) return null;

        // 取最新的（按修改时间）
        File latest = files[0];
        for (int i = 1; i < files.length; i++) {
            if (files[i].lastModified() > latest.lastModified()) {
                latest = files[i];
            }
        }
        return latest.getAbsolutePath();
    }

    private static String extractVersion(String jarName) {
        // peacehaven-1.2.0.jar → 1.2.0
        String name = jarName.replace(PROJECT_NAME + "-", "").replace(".jar", "");
        return name.isEmpty() ? "unknown" : name;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
