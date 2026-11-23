package com.macro.mall.common.domain;

import lombok.Data;
import java.util.List;

@Data
public class OrderCloseRequest {
    private List<Long> ids;
    private String operator;
    private String note;
}
