package com.macro.mall.config;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;

@Configuration
public class InternalRouteFilter {
    @Bean
    @Order(-1000)
    public GlobalFilter denyInternalRoutes() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getURI().getPath();
            if (path.matches("(?i).*/internal(?:/.*)?")) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }
}
