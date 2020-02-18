package com.atguigu.gmalllogger.gmalllogger.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.atguigu.GmallConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class LoggerController {

    @Autowired
    KafkaTemplate<String,String> kafkaTemplate;
    @PostMapping("log")
    public String parseLog(@RequestParam("logString") String logStr){
        //补充时间戳
        JSONObject jsonObject = JSON.parseObject(logStr);
        jsonObject.put("ts",System.currentTimeMillis());
        //落盘
        String jsonStr = jsonObject.toJSONString();
        log.info(jsonStr);

        //推送到kafka
        //按照topic进行分类
        if(jsonStr.contains("startup")){
            kafkaTemplate.send(GmallConstants.KAFKA_TOPIC_STARTUP,jsonStr);
        }else{
            kafkaTemplate.send(GmallConstants.KAFKA_TOPIC_EVENT,jsonStr);
        }
        return "success";
    }
}
