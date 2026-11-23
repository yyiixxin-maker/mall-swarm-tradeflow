package com.macro.mall.service;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.domain.OrderCloseRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "mall-portal", contextId = "portalOrderClient")
public interface PortalOrderClient {
    @PostMapping("/internal/orders/close")
    CommonResult<Integer> close(@RequestHeader("X-TradeFlow-Internal") String token, @RequestBody OrderCloseRequest request);
}
