package com.zhu.ragdemo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

/**
 *
 * @author ：zhuzj
 * @date ：2026/10/16 14:51
 */
//@Configuration
public class RedisConfig {

    @Bean
    public JedisConnectionFactory jedisConnectionFactory() {
        // 这里的 host 和 port 会从 application.yml 的 spring.data.redis 配置中读取
        // 也可以在 application.yml 中显式配置 spring.data.redis.host 和 port
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        return new JedisConnectionFactory(config);
    }

}
