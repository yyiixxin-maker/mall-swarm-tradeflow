package com.macro.mall.portal.controller;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.domain.OrderCloseRequest;
import com.macro.mall.portal.service.OrderStateService;
import com.macro.mall.portal.component.CancelOrderSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {
    private final OrderStateService stateService;
    private final String token;
    private final CancelOrderSender sender;

    public InternalOrderController(OrderStateService stateService, CancelOrderSender sender, @Value("${tradeflow.internal-token:}") String token) {
        this.stateService = stateService;
        this.token = token;
        this.sender = sender;
    }

    @PostMapping("/close")
    public CommonResult<Integer> close(@RequestHeader(value = "X-TradeFlow-Internal", defaultValue = "") String supplied,
                                      @RequestBody OrderCloseRequest request) {
        authorize(supplied);
        return CommonResult.success(stateService.close(request));
    }

    @PostMapping("/replay-timeouts")
    public CommonResult<Integer> replay(@RequestHeader(value = "X-TradeFlow-Internal", defaultValue = "") String supplied,
                                       @RequestParam(defaultValue = "10") int limit) {
        authorize(supplied);
        return CommonResult.success(sender.replayFailed(limit));
    }

    private void authorize(String supplied) {
        if (token.length() < 32 || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
