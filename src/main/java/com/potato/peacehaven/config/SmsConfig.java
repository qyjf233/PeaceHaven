package com.potato.peacehaven.config;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsConfig {

    @Value("${aliyun.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.access-key-secret}")
    private String accessKeySecret;

    @Bean
    public IAcsClient acsClient() {
        DefaultProfile profile = DefaultProfile.getProfile(
                "cn-hangzhou", accessKeyId, accessKeySecret);
        return new DefaultAcsClient(profile);
    }
}
