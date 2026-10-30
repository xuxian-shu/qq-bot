package com.qqbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling//开启 Spring 定时任务（每日一题的 @Scheduled 靠它生效）
@SpringBootApplication
public class QqBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(QqBotApplication.class, args);
    }

}