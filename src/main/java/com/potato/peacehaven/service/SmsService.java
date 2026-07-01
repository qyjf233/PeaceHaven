package com.potato.peacehaven.service;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.google.gson.JsonObject;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class SmsService {

    private final AsyncClient smsClient;
    private final String signName;
    private final String templateCode;

    public SmsService(
            @Value("${aliyun.access-key-id}") String accessKeyId,
            @Value("${aliyun.access-key-secret}") String accessKeySecret,
            @Value("${aliyun.sms.sign-name}") String signName,
            @Value("${aliyun.sms.template-code}") String templateCode
    ) {
        this.signName = signName;
        this.templateCode = templateCode;

        StaticCredentialProvider credentialProvider = StaticCredentialProvider.create(
                Credential.builder()
                        .accessKeyId(accessKeyId)
                        .accessKeySecret(accessKeySecret)
                        .build()
        );

        this.smsClient = AsyncClient.builder()
                .credentialsProvider(credentialProvider)
                .region("cn-hangzhou")
                .build();
    }

    @PreDestroy
    public void destroy() throws Exception {
        if (smsClient != null) {
            smsClient.close();
        }
    }

    /**
     * 发送短信验证码
     */
    public CompletableFuture<SendSmsVerifyCodeResponse> sendVerifyCode(String phone) {
        SendSmsVerifyCodeRequest request = SendSmsVerifyCodeRequest.builder()
                .phoneNumber(phone)
                .signName(signName)
                .templateCode(templateCode)
                .templateParam("{\"code\":\"##code##\",\"min\":\"5\"}")
                .build();

        log.info("发送验证码到手机号: {}", phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2"));

        return smsClient.sendSmsVerifyCode(request)
                .thenApply(response -> {
                    log.info("验证码发送结果: {}", response.getBody().getSuccess());
                    return response;
                })
                .exceptionally(ex -> {
                    log.error("发送验证码失败: {}", ex.getMessage(), ex);
                    throw new RuntimeException("发送验证码失败", ex);
                });
    }

    /**
     * 校验短信验证码
     */
    public CompletableFuture<Boolean> checkVerifyCode(String phone, String code) {
        CheckSmsVerifyCodeRequest request = CheckSmsVerifyCodeRequest.builder()
                .phoneNumber(phone)
                .verifyCode(code)
                .countryCode("86") // 默认 86
                .build();

        return smsClient.checkSmsVerifyCode(request)
                .thenApply(response -> {
                    log.info("验证码校验结果: {}", response.getBody());
                    // checkSmsVerifyCode 成功即表示验证码正确
                    return true;
                })
                .exceptionally(ex -> {
                    log.warn("验证码校验失败: {}", ex.getMessage());
                    return false;
                });
    }
}
