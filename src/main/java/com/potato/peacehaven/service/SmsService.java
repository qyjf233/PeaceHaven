package com.potato.peacehaven.service;

import com.aliyuncs.IAcsClient;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsRequest;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsResponse;
import com.potato.peacehaven.entity.SmsVerificationCode;
import com.potato.peacehaven.repository.SmsVerificationCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {

    private final IAcsClient acsClient;
    private final SmsVerificationCodeRepository codeRepository;

    @Value("${aliyun.sms.sign-name}")
    private String signName;

    @Value("${aliyun.sms.template-code}")
    private String templateCode;

    @Value("${aliyun.sms.code-length}")
    private int codeLength;

    @Value("${aliyun.sms.expire-minutes}")
    private int expireMinutes;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成验证码并通过阿里云短信发送
     * @return 发送成功返回null，失败返回错误信息
     */
    @Transactional
    public String sendCode(String phone) {
        // 生成随机验证码
        String code = generateCode();

        // 存入数据库
        SmsVerificationCode record = SmsVerificationCode.builder()
                .phone(phone)
                .code(code)
                .expireAt(LocalDateTime.now().plusMinutes(expireMinutes))
                .build();
        codeRepository.save(record);

        // 调用阿里云短信API
        try {
            SendSmsRequest request = new SendSmsRequest();
            request.setPhoneNumbers(phone);
            request.setSignName(signName);
            request.setTemplateCode(templateCode);
            request.setTemplateParam("{\"code\":\"" + code + "\"}");

            SendSmsResponse response = acsClient.getAcsResponse(request);

            if ("OK".equals(response.getCode())) {
                log.info("验证码发送成功: phone={}", phone);
                return null;
            } else {
                log.warn("短信发送失败: code={}, message={}", response.getCode(), response.getMessage());
                return "短信发送失败，请稍后重试";
            }
        } catch (Exception e) {
            log.error("短信发送异常: phone={}", phone, e);
            return "短信服务异常，请稍后重试";
        }
    }

    /**
     * 校验验证码：匹配 + 未过期 + 未使用
     */
    @Transactional
    public boolean verifyCode(String phone, String code) {
        Optional<SmsVerificationCode> opt = codeRepository
                .findFirstByPhoneAndUsedFalseOrderByCreatedAtDesc(phone);

        if (opt.isEmpty()) return false;

        SmsVerificationCode record = opt.get();

        // 检查是否过期
        if (record.getExpireAt().isBefore(LocalDateTime.now())) {
            return false;
        }

        // 检查验证码是否匹配
        if (!record.getCode().equals(code)) {
            return false;
        }

        // 标记为已使用
        record.setUsed(true);
        codeRepository.save(record);
        return true;
    }

    /**
     * 生成指定位数的数字验证码
     */
    private String generateCode() {
        int bound = (int) Math.pow(10, codeLength);
        int code = RANDOM.nextInt(bound);
        return String.format("%0" + codeLength + "d", code);
    }
}
