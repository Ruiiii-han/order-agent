package com.example.orderagent.sandbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
public class LlmSettings {
    private String baseUrl = "https://api.deepseek.com";
    private String apiKey = "";
    private String model = "deepseek-chat";
    private double temperature = 0.2;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 60000;
    private double priceInPerMTokens = 1.0;
    private double priceOutPerMTokens = 2.0;
}