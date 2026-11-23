package com.macro.mall.portal.service;

import com.macro.mall.common.exception.Asserts;
import com.macro.mall.common.domain.OrderCloseRequest;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.TradeOrderDao;
import com.macro.mall.portal.domain.PaymentTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** Owns the order-state transaction; HTTP, MQ and admin all use this service. */
@Slf4j
@Service
public class OrderStateService {
    @Autowired private TradeOrderDao tradeDao;
    @Autowired private OmsOrderItemMapper itemMapper;
    @Autowired private OmsOrderSettingMapper settingMapper;
    @Autowired private OmsOrderOperateHistoryMapper historyMapper;
    @Autowired private UmsMemberCacheService memberCacheService;

    public static SortedMap<Long, Integer> quantities(List<OmsOrderItem> items) {
        if (items == null || items.isEmpty()) Asserts.fail("订单商品不能为空");
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        for (OmsOrderItem item : items) {
            if (item.getProductSkuId() == null || item.getProductQuantity() == null || item.getProductQuantity() <= 0) {
                Asserts.fail("SKU及商品数量必须有效且大于零");
            }
            try {
                quantities.merge(item.getProductSkuId(), item.getProductQuantity(), Math::addExact);
            } catch (ArithmeticException e) {
                Asserts.fail("商品数量超出范围");
            }
        }
        return quantities;
    }

    @Transactional
    public void reserve(List<OmsOrderItem> items) {
        SortedMap<Long, Integer> quantities = quantities(items);
        for (Long id : quantities.keySet()) {
            PmsSkuStock sku = tradeDao.lockSku(id);
            if (sku == null || items.stream().anyMatch(i -> id.equals(i.getProductSkuId()) && !Objects.equals(sku.getProductId(), i.getProductId()))) {
                Asserts.fail("SKU与商品不匹配");
            }
            if (tradeDao.reserve(id, quantities.get(id)) != 1) Asserts.fail("库存不足，无法下单");
        }
    }

    private List<OmsOrderItem> items(Long orderId) {
        OmsOrderItemExample example = new OmsOrderItemExample();
        example.createCriteria().andOrderIdEqualTo(orderId);
        return itemMapper.selectByExample(example);
    }

    private boolean lockReservations(SortedMap<Long, Integer> quantities) {
        boolean valid = true;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            PmsSkuStock sku = tradeDao.lockSku(entry.getKey());
            valid &= sku != null && sku.getStock() != null && sku.getLockStock() != null
                    && sku.getStock() >= sku.getLockStock() && sku.getLockStock() >= entry.getValue();
        }
        return valid;
    }

    public long deadline(OmsOrder order) {
        Long deadline = tradeDao.deadline(order.getId());
        if (deadline != null) return deadline;
        OmsOrderSetting setting = settingMapper.selectByPrimaryKey(1L);
        if (order.getCreateTime() == null || setting == null || setting.getNormalOrderOvertime() == null || setting.getNormalOrderOvertime() <= 0) {
            Asserts.fail("订单缺少有效的超时时间，需人工核对");
        }
        return Math.addExact(order.getCreateTime().getTime(), Math.multiplyExact(setting.getNormalOrderOvertime().longValue(), 60_000L));
    }

    @Transactional
    public int close(OrderCloseRequest request) {
        if (request == null || request.getIds() == null || request.getIds().isEmpty() || request.getIds().size() > 100
                || request.getIds().stream().anyMatch(id -> id == null || id <= 0)
                || request.getOperator() == null || request.getOperator().isBlank() || request.getOperator().length() > 32
                || request.getNote() == null || request.getNote().length() > 400) Asserts.fail("关闭订单参数无效");
        List<Long> ids = new ArrayList<>(new TreeSet<>(request.getIds()));
        SortedSet<Long> skuIds = new TreeSet<>();
        for (Long id : ids) {
            OmsOrder order = tradeDao.lockOrder(id);
            if (order == null) Asserts.fail("订单不存在");
            if (!Integer.valueOf(0).equals(order.getStatus()) && !Integer.valueOf(4).equals(order.getStatus())) Asserts.fail("只能关闭待付款订单");
            if (Integer.valueOf(0).equals(order.getStatus())) skuIds.addAll(quantities(items(id)).keySet());
        }
        for (Long skuId : skuIds) tradeDao.lockSku(skuId);
        int count = 0;
        for (Long id : ids) count += cancel(id, null, false, request.getOperator(), "后台关闭：" + request.getNote());
        return count;
    }

    @Transactional
    public int cancel(Long orderId, Long memberId, boolean onlyExpired, String operator, String note) {
        OmsOrder order = tradeDao.lockOrder(orderId);
        if (onlyExpired && memberId == null && (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus()))) return 0;
        if (order == null || !Integer.valueOf(0).equals(order.getDeleteStatus())
                || (memberId != null && !memberId.equals(order.getMemberId()))) Asserts.fail("订单不存在或无权操作");
        if (!Integer.valueOf(0).equals(order.getStatus())) return 0;
        if (onlyExpired && System.currentTimeMillis() < deadline(order)) return 0;
        // Verified money, including a late payment, must be reconciled before releasing resources.
        if (tradeDao.payment(order.getOrderSn()) != null) Asserts.fail("订单存在支付记录，需先核对支付结果");
        SortedMap<Long, Integer> quantities = quantities(items(orderId));
        if (!lockReservations(quantities)) Asserts.fail("订单库存预留异常，需人工核对");
        if (tradeDao.transition(orderId, 4, new Date()) != 1) return 0;
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            if (tradeDao.release(entry.getKey(), entry.getValue()) != 1) Asserts.fail("解除库存预留失败");
        }
        if (order.getCouponId() != null && tradeDao.refundCoupon(orderId, order.getMemberId()) != 1) {
            Asserts.fail("优惠券未绑定此订单，需人工核对；订单未关闭");
        }
        if (order.getUseIntegration() != null && order.getUseIntegration() > 0) {
            if (tradeDao.refundPoints(order.getMemberId(), order.getUseIntegration()) != 1) Asserts.fail("返还积分失败");
            evictMemberAfterCommit(order.getMemberId());
        }
        history(orderId, 4, operator, note);
        return 1;
    }

    @Transactional
    public int applyPayment(PaymentTransaction fact) {
        OmsOrder order = tradeDao.lockOrder(fact.getOrderId());
        if (order == null || !Objects.equals(order.getOrderSn(), fact.getMerchantOrderNo())) Asserts.fail("支付订单不存在");
        PaymentTransaction previous = tradeDao.payment(order.getOrderSn());
        if (previous != null) {
            if (!Objects.equals(previous.getProviderTradeNo(), fact.getProviderTradeNo())
                    || previous.getAmount().compareTo(fact.getAmount()) != 0
                    || !Objects.equals(previous.getAppId(), fact.getAppId()) || !Objects.equals(previous.getSellerId(), fact.getSellerId())) {
                Asserts.fail("支付流水绑定冲突");
            }
            return "APPLIED".equals(previous.getState()) ? 1 : 0;
        }
        fact.setState("REVIEW");
        if (order.getPayAmount() == null || order.getPayAmount().compareTo(fact.getAmount()) != 0) {
            fact.setReviewReason("AMOUNT_MISMATCH");
        } else if (!Integer.valueOf(0).equals(order.getStatus()) || !Integer.valueOf(0).equals(order.getDeleteStatus())) {
            fact.setReviewReason("ORDER_NOT_PENDING");
        } else {
            List<OmsOrderItem> items = items(order.getId());
            if (items.isEmpty()) {
                fact.setReviewReason("RESERVATION_MISSING");
            } else {
                SortedMap<Long, Integer> quantities = quantities(items);
                if (!lockReservations(quantities)) {
                    fact.setReviewReason("RESERVATION_MISMATCH");
                } else {
                    if (tradeDao.transition(order.getId(), 1, new Date()) != 1) Asserts.fail("订单支付状态冲突");
                    for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                        if (tradeDao.deduct(entry.getKey(), entry.getValue()) != 1) Asserts.fail("支付扣减库存失败");
                    }
                    fact.setState("APPLIED");
                    history(order.getId(), 1, "ALIPAY", "支付渠道验证完成");
                }
            }
        }
        fact.setVerifiedAt(System.currentTimeMillis());
        tradeDao.insertPayment(fact);
        if ("REVIEW".equals(fact.getState())) log.warn("Payment requires review: orderId={}, reason={}", order.getId(), fact.getReviewReason());
        return "APPLIED".equals(fact.getState()) ? 1 : 0;
    }

    public void evictMemberAfterCommit(Long memberId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { memberCacheService.delMember(memberId); }
                catch (RuntimeException e) { log.warn("Member cache invalidation failed: memberId={}", memberId); }
            }
        });
    }

    private void history(Long orderId, int status, String operator, String note) {
        OmsOrderOperateHistory history = new OmsOrderOperateHistory();
        history.setOrderId(orderId);
        history.setCreateTime(new Date());
        history.setOrderStatus(status);
        history.setOperateMan(operator);
        history.setNote(note);
        historyMapper.insertSelective(history);
    }
}
