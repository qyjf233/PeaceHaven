package com.potato.peacehaven;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@SpringBootApplication
@EnableScheduling
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 2592000)  // Session 30天
public class PeacehavenApplication {

	public static void main(String[] args) {
		SpringApplication.run(PeacehavenApplication.class, args);
	}

}