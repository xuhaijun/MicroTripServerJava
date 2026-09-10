package com.microtrip.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 微旅途后端启动入口（Spring Boot 重写版）。
 *
 * <p>与原 Node 版（MicroTripServer）保持<b>完全一致的 REST 契约</b>，
 * Flutter 端 AuthService / SyncService 无需任何改动即可对接。</p>
 *
 * <p>启动方式：
 * <pre>
 *   mvn spring-boot:run
 *   # 或打包后
 *   java -jar target/micro-trip-server.jar
 * </pre>
 * </p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class MicroTripServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MicroTripServerApplication.class, args);
    }
}
