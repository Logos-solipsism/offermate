package com.offermate;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@MapperScan("com.offermate.**.mapper")
@SpringBootApplication
public class OfferMateApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfferMateApplication.class, args);
    }
}
