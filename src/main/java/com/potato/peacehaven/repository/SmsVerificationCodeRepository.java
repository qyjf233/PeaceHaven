package com.potato.peacehaven.repository;

import com.potato.peacehaven.entity.SmsVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SmsVerificationCodeRepository extends JpaRepository<SmsVerificationCode, Long> {

    /** 查询某手机号最新一条未使用的验证码 */
    Optional<SmsVerificationCode> findFirstByPhoneAndUsedFalseOrderByCreatedAtDesc(String phone);
}
