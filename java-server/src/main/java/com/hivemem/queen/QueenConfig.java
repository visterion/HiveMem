package com.hivemem.queen;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class QueenConfig {

    @Bean
    public VistierieAgentClient vistierieAgentClient(RestClient.Builder builder, QueenProperties props) {
        return new VistierieAgentClient(builder, props);
    }

    @Bean
    public VistierieRunsClient vistierieRunsClient(RestClient.Builder builder, QueenProperties props) {
        return new VistierieRunsClient(builder, props);
    }
}
