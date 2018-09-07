package com.laowang.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author wangyonghao
 */
@RestController
public class DcController {

    @Autowired
    DiscoveryClient discoveryClient;

    @RequestMapping("/dc")
    public String allClient(){
        String service = "Service: " + discoveryClient.getServices();
        System.out.println(service);

        return service;
    }
}
