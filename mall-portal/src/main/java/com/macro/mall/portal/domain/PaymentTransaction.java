package com.macro.mall.portal.domain;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class PaymentTransaction {
    private String merchantOrderNo;
    private String providerTradeNo;
    private Long orderId;
    private BigDecimal amount;
    private String appId;
    private String sellerId;
    private String tradeStatus;
    private String state;
    private String reviewReason;
    private Long verifiedAt;
}
