package com.macro.mall;

import com.macro.mall.config.InternalRouteFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import static org.junit.jupiter.api.Assertions.*;

class InternalRouteReliabilityTests {
    @Test void publicGatewayRejectsServiceInternalPaths() {
        var filter=new InternalRouteFilter().denyInternalRoutes();
        for(String path: new String[]{"/mall-portal/internal/orders/close","/internal/orders/close","/mall-portal/%69nternal/orders/close"}) {
            var exchange=MockServerWebExchange.from(MockServerHttpRequest.method(org.springframework.http.HttpMethod.POST,java.net.URI.create(path)));
            filter.filter(exchange,e -> { fail("Internal request reached the downstream chain");return reactor.core.publisher.Mono.empty(); }).block();
            assertEquals(HttpStatus.FORBIDDEN,exchange.getResponse().getStatusCode());
        }
    }
}
