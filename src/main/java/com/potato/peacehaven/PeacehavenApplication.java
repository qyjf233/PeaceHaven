package com.potato.peacehaven;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;

@SpringBootApplication
@EnableJdbcHttpSession(maxInactiveIntervalInSeconds = 2592000)  // Session 30天
public class PeacehavenApplication {

	public static void main(String[] args) {
		SpringApplication.run(PeacehavenApplication.class, args);
	}

}
能不能实现在我的管理员页面新增一个机器人管理，然后里面放着机器人所有的配置和开关，比如第一次运行的时候，我部署完项目，进入后台，点开机器人管理，然后启动机器人，扫码就可以登录？bot进程可以