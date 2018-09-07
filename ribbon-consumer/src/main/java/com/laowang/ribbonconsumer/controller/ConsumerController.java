package com.laowang.ribbonconsumer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * @Author wangyonghao
 */
@RestController
public class ConsumerController {

    @Autowired
    private RestTemplate restTemplate;


    @RequestMapping("/ribbonConsumer")
    public String  hello(){
        System.out.println("hello!");
        return restTemplate.getForEntity("http://eureka-client/dc", String.class).toString();
    }

}
