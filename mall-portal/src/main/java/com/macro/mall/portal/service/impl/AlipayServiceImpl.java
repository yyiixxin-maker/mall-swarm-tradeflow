package com.macro.mall.portal.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.common.exception.Asserts;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.model.OmsOrderExample;
import com.macro.mall.portal.config.AlipayConfig;
import com.macro.mall.portal.dao.TradeOrderDao;
import com.macro.mall.portal.domain.*;
import com.macro.mall.portal.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
@Service
public class AlipayServiceImpl implements AlipayService {
    @Autowired private AlipayConfig alipayConfig;
    @Autowired private AlipayClient alipayClient;
    @Autowired private OmsOrderMapper orderMapper;
    @Autowired private UmsMemberService memberService;
    @Autowired private OrderStateService orderStateService;
    @Autowired private TradeOrderDao tradeDao;

    private void requireConfigured() {
        if (!StringUtils.hasText(alipayConfig.getAppId()) || !StringUtils.hasText(alipayConfig.getSellerId())
                || !StringUtils.hasText(alipayConfig.getAlipayPublicKey()) || !StringUtils.hasText(alipayConfig.getAppPrivateKey())) {
            Asserts.fail("支付宝商户配置未完成");
        }
    }

    private OmsOrder find(String sn, boolean requireOwner) {
        if (!StringUtils.hasText(sn) || sn.length() > 64) Asserts.fail("请提供有效的商户订单号");
        OmsOrderExample example = new OmsOrderExample();
        example.createCriteria().andOrderSnEqualTo(sn);
        List<OmsOrder> orders = orderMapper.selectByExample(example);
        if (orders.size() != 1) Asserts.fail("订单不存在或订单号异常");
        OmsOrder order = orders.get(0);
        if (requireOwner && (!memberService.getCurrentMember().getId().equals(order.getMemberId())
                || !Integer.valueOf(0).equals(order.getDeleteStatus()))) Asserts.fail("订单不存在或无权操作");
        return order;
    }

    private JSONObject payContent(AliPayParam param, String productCode) {
        requireConfigured();
        OmsOrder order = find(param == null ? null : param.getOutTradeNo(), true);
        if (!Integer.valueOf(0).equals(order.getStatus()) || System.currentTimeMillis() >= orderStateService.deadline(order)
                || tradeDao.payment(order.getOrderSn()) != null) Asserts.fail("订单当前不可发起支付");
        JSONObject content = new JSONObject();
        content.put("out_trade_no", order.getOrderSn());
        content.put("total_amount", money(order.getPayAmount().toPlainString()).toPlainString());
        content.put("subject", "商城订单 " + order.getOrderSn());
        content.put("seller_id", alipayConfig.getSellerId());
        content.put("product_code", productCode);
        return content;
    }

    @Override
    public String pay(AliPayParam param) {
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setNotifyUrl(alipayConfig.getNotifyUrl());
        request.setReturnUrl(alipayConfig.getReturnUrl());
        request.setBizContent(payContent(param, "FAST_INSTANT_TRADE_PAY").toJSONString());
        try { return alipayClient.pageExecute(request).getBody(); }
        catch (AlipayApiException e) { throw channelError(); }
    }

    @Override
    public String webPay(AliPayParam param) {
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
        request.setNotifyUrl(alipayConfig.getNotifyUrl());
        request.setReturnUrl(alipayConfig.getReturnUrl());
        request.setBizContent(payContent(param, "QUICK_WAP_WAY").toJSONString());
        try { return alipayClient.pageExecute(request).getBody(); }
        catch (AlipayApiException e) { throw channelError(); }
    }

    @Override
    public String notify(Map<String, String> params) {
        try {
            params = new HashMap<>(params);
            requireConfigured();
            if (!AlipaySignature.rsaCheckV1(params, alipayConfig.getAlipayPublicKey(), alipayConfig.getCharset(), alipayConfig.getSignType())) return "failure";
            if (!alipayConfig.getAppId().equals(params.get("app_id")) || !alipayConfig.getSellerId().equals(params.get("seller_id"))) return "failure";
            if (!paid(params.get("trade_status"))) return "failure";
            OmsOrder order = find(params.get("out_trade_no"), false);
            apply(order, params.get("trade_no"), params.get("total_amount"), params.get("trade_status"));
            return "success";
        } catch (Exception e) {
            // Never log signed parameters, SDK responses, keys or customer details.
            log.warn("Alipay notification rejected: {}", e.getClass().getSimpleName());
            return "failure";
        }
    }

    @Override
    public String query(String outTradeNo, String tradeNo) {
        requireConfigured();
        if (!StringUtils.hasText(outTradeNo)) {
            if (!StringUtils.hasText(tradeNo) || tradeNo.length() > 64) Asserts.fail("请提供订单号或已绑定的支付流水号");
            outTradeNo = tradeDao.merchantOrderNo(tradeNo);
        }
        OmsOrder order = find(outTradeNo, true);
        if (StringUtils.hasText(tradeNo) && !order.getOrderSn().equals(tradeDao.merchantOrderNo(tradeNo))) Asserts.fail("支付流水未绑定此订单");
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        JSONObject content = new JSONObject();
        content.put("out_trade_no", order.getOrderSn());
        request.setBizContent(content.toJSONString());
        AlipayTradeQueryResponse response;
        try { response = alipayClient.execute(request); }
        catch (AlipayApiException e) { throw channelError(); }
        if (response == null || !response.isSuccess()) throw channelError();
        if (!order.getOrderSn().equals(response.getOutTradeNo())) Asserts.fail("支付查询订单号不匹配");
        if (paid(response.getTradeStatus())) {
            // The SDK verifies the signed response for our configured app; query has no seller_id field.
            apply(order, response.getTradeNo(), response.getTotalAmount(), response.getTradeStatus());
        }
        if (!Set.of("WAIT_BUYER_PAY", "TRADE_CLOSED", "TRADE_SUCCESS", "TRADE_FINISHED").contains(Objects.toString(response.getTradeStatus(), ""))) throw channelError();
        return response.getTradeStatus();
    }

    private void apply(OmsOrder order, String tradeNo, String amount, String status) {
        if (!StringUtils.hasText(tradeNo) || tradeNo.length() > 64) Asserts.fail("支付流水号无效");
        PaymentTransaction fact = new PaymentTransaction();
        fact.setOrderId(order.getId());
        fact.setMerchantOrderNo(order.getOrderSn());
        fact.setProviderTradeNo(tradeNo);
        fact.setAmount(money(amount));
        fact.setAppId(alipayConfig.getAppId());
        fact.setSellerId(alipayConfig.getSellerId());
        fact.setTradeStatus(status);
        orderStateService.applyPayment(fact);
    }

    static BigDecimal money(String value) {
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0 || amount.precision() > 10) throw new IllegalArgumentException();
            return amount;
        } catch (RuntimeException e) {
            throw new ApiException("支付金额无效");
        }
    }

    private static boolean paid(String status) { return "TRADE_SUCCESS".equals(status) || "TRADE_FINISHED".equals(status); }

    private ApiException channelError() { return new ApiException("支付渠道查询失败，请稍后重试"); }
}
