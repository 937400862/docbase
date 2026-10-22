package com.zhu.docbase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 【改动 2026-10-22】加了 @EnableScheduling，给知识库体检/修复的定时任务用。
 * 定时任务本身默认关闭（rag.maintenance.enabled=false），不加这个注解的话
 * 即使打开了配置也不会触发。
 */
@SpringBootApplication
@EnableScheduling
public class DocbaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocbaseApplication.class, args);
    }

}
