package com.omnibank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Clock;

@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration.class,
    org.springframework.boot.autoconfigure.jms.activemq.ActiveMQAutoConfiguration.class,
    org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration.class
})
@ComponentScan(basePackages = "com.omnibank")
@EntityScan(basePackages = "com.omnibank")
@EnableJpaRepositories(basePackages = "com.omnibank")
public class OmnibankApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmnibankApplication.class, args);
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
