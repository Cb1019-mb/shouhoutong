package com.sht;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.sht.**.mapper")
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class ShtApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShtApplication.class, args);
    }
}
