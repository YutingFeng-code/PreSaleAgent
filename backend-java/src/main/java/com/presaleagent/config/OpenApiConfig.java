package com.presaleagent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI preSaleAgentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("PreSaleAgent Java API")
                        .version("0.1.0")
                        .description("PreSaleAgent Java 售前导购接口文档，支持 /chat、/search、知识库、监控和评测接口。")
                        .license(new License().name("Internal Project")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Java App"),
                        new Server().url("http://localhost:8081").description("Local Nginx Proxy")
                ));
    }
}
