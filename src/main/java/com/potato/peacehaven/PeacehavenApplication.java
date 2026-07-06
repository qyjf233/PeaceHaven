package com.potato.peacehaven;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@SpringBootApplication
@EnableJdbcHttpSession  // 启用 Spring Session JDBC，将 Session 持久化到数据库
public class PeacehavenApplication {

	public static void main(String[] args) {
		SpringApplication.run(PeacehavenApplication.class, args);
	}

}
