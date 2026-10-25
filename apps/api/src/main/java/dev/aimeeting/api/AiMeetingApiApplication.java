package dev.aimeeting.api;

import dev.aimeeting.api.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class AiMeetingApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiMeetingApiApplication.class, args);
    }
}
