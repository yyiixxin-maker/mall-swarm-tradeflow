package com.macro.mall.portal.domain;

import lombok.Data;

@Data
public class OutboxEvent {
    private String eventId;
    private Long orderId;
    private Long deadline;
    private Integer attempts;
}
