package com.notifyd;

import com.notifyd.config.NotifyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifydApplication {
	public static void main(String[] args) {
		SpringApplication.run(NotifydApplication.class, args);
	}
}

