package io.github.dekkerding.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * engine-server 启动类（:8081，核心 DDD 业务模块）
 *
 * <p>【教学注释 · Spring Boot 启动原理】
 * 1. {@code @SpringBootApplication} 是三个注解的组合：
 *    - {@code @Configuration}：本类也是一个配置类（可以声明 @Bean 方法）
 *    - {@code @EnableAutoConfiguration}：按 classpath 里的依赖自动装配（引入 starter-web 就有 Tomcat）
 *    - {@code @ComponentScan}：从本包开始扫描 @Component/@Service/@Repository 等注解
 * 2. {@code SpringApplication.run} 做的事：启动内嵌 Tomcat → 初始化 IoC 容器 → 执行所有 @PostConstruct
 *
 * <p>【调用链全景】浏览器 → :8090 网关 → /api/** 反代 → 本应用(:8081) → PythonChannel → Python 子进程
 */
@SpringBootApplication
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
