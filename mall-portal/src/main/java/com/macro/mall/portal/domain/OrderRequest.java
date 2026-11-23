package com.macro.mall.portal.domain;

import lombok.Data;

@Data
public class OrderRequest {
    private String requestHash;
    private Long orderId;
}
