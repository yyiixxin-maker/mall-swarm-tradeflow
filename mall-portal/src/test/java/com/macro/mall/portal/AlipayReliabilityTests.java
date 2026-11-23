package com.macro.mall.portal;

import com.alibaba.fastjson.JSONObject;
import com.alipay.api.*;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.model.*;
import com.macro.mall.portal.dao.TradeOrderDao;
import com.macro.mall.portal.domain.AliPayParam;
import com.macro.mall.portal.service.*;
import com.macro.mall.portal.service.impl.AlipayServiceImpl;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlipayReliabilityTests {
    private AlipayServiceImpl service;
    private AlipayClient client;
    private OrderStateService states;
    private UmsMemberService members;
    private TradeOrderDao dao;
    private OmsOrder order;

    @BeforeEach void setup() {
        service = new AlipayServiceImpl();
        client = mock(AlipayClient.class);
        states = mock(OrderStateService.class);
        members = mock(UmsMemberService.class);
        dao = mock(TradeOrderDao.class);
        OmsOrderMapper mapper = mock(OmsOrderMapper.class);
        order = new OmsOrder(); order.setId(7L); order.setOrderSn("test-order");
        order.setMemberId(42L); order.setPayAmount(new BigDecimal("10.00")); order.setStatus(0); order.setDeleteStatus(0);
        when(mapper.selectByExample(any())).thenReturn(List.of(order));
        UmsMember member = new UmsMember(); member.setId(42L);
        when(members.getCurrentMember()).thenReturn(member);
        when(states.deadline(any())).thenReturn(System.currentTimeMillis()+60_000);
        ReflectionTestUtils.setField(service,"alipayConfig",PaymentTestSupport.config());
        ReflectionTestUtils.setField(service,"alipayClient",client);
        ReflectionTestUtils.setField(service,"orderMapper",mapper);
        ReflectionTestUtils.setField(service,"memberService",members);
        ReflectionTestUtils.setField(service,"orderStateService",states);
        ReflectionTestUtils.setField(service,"tradeDao",dao);
    }

    @Test void payUsesPersistedAmountAndServerSubject() throws Exception {
        AlipayTradePagePayResponse response = new AlipayTradePagePayResponse(); response.setBody("form");
        when(client.pageExecute(any(AlipayTradePagePayRequest.class))).thenReturn(response);
        AliPayParam param = new AliPayParam(); param.setOutTradeNo(order.getOrderSn()); param.setTotalAmount(new BigDecimal("0.01")); param.setSubject("client-controlled");
        assertEquals("form", service.pay(param));
        ArgumentCaptor<AlipayTradePagePayRequest> request = ArgumentCaptor.forClass(AlipayTradePagePayRequest.class);
        verify(client).pageExecute(request.capture());
        JSONObject content = JSONObject.parseObject(request.getValue().getBizContent());
        assertEquals("10.00", content.getString("total_amount"));
        assertEquals("商城订单 test-order", content.getString("subject"));
    }

    @Test void anotherMemberCannotPayOrQuery() {
        UmsMember other = new UmsMember(); other.setId(43L); when(members.getCurrentMember()).thenReturn(other);
        AliPayParam param = new AliPayParam(); param.setOutTradeNo(order.getOrderSn());
        assertThrows(ApiException.class, () -> service.pay(param));
        assertThrows(ApiException.class, () -> service.query(order.getOrderSn(),null));
        verifyNoInteractions(client,states);
    }

    @Test void verifiedNotificationInvokesTransactionalOwner() throws Exception {
        Map<String,String> params=PaymentTestSupport.signed(order.getOrderSn(),"trade-7","10.00");
        assertEquals("success", service.notify(Map.copyOf(params)));
        assertTrue(params.containsKey("sign"));
        verify(states).applyPayment(argThat(p -> p.getOrderId().equals(7L) && p.getAmount().equals(new BigDecimal("10.00"))));
    }

    @Test void alteredSignatureDoesNotMutateOrder() throws Exception {
        Map<String,String> params = PaymentTestSupport.signed(order.getOrderSn(),"trade-7","10.00");
        params.put("total_amount","0.01");
        assertEquals("failure",service.notify(params));
        verifyNoInteractions(states);
    }

    @Test void validSignatureForAnotherMerchantIsRejected() throws Exception {
        for (String field : List.of("app_id","seller_id")) {
            Map<String,String> params = PaymentTestSupport.signed(order.getOrderSn(),"trade-7","10.00");
            params.put(field,"other-merchant"); PaymentTestSupport.sign(params);
            assertEquals("failure",service.notify(params));
        }
        verifyNoInteractions(states);
    }

    @Test void unconfiguredMerchantAndInvalidAmountsFailClosed() throws Exception {
        Map<String,String> params = PaymentTestSupport.signed(order.getOrderSn(),"trade-7","10.001");
        assertEquals("failure",service.notify(params));
        ReflectionTestUtils.setField(service,"alipayConfig",new com.macro.mall.portal.config.AlipayConfig());
        assertEquals("failure",service.notify(PaymentTestSupport.signed(order.getOrderSn(),"trade-7","10.00")));
        verifyNoInteractions(states);
    }

    @Test void transactionFailureReturnsFailureForProviderRetry() throws Exception {
        when(states.applyPayment(any())).thenThrow(new IllegalStateException("simulated rollback"));
        assertEquals("failure", service.notify(PaymentTestSupport.signed(order.getOrderSn(),"trade-7","10.00")));
    }

    @Test void finishedTradeIsAlsoAConfirmedPayment() throws Exception {
        Map<String,String> params = PaymentTestSupport.signed(order.getOrderSn(),"trade-7","10.00");
        params.put("trade_status","TRADE_FINISHED"); PaymentTestSupport.sign(params);
        assertEquals("success", service.notify(params));
        verify(states).applyPayment(any());
    }

    @Test void sdkExceptionAndFailureCodeAreRetryableErrors() throws Exception {
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenThrow(new AlipayApiException("simulated timeout"));
        assertThrows(ApiException.class, () -> service.query(order.getOrderSn(),null));
        reset(client);
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("40004");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        assertThrows(ApiException.class, () -> service.query(order.getOrderSn(),null));
        verifyNoInteractions(states);
    }

    @Test void tradeNumberOnlyQueryResolvesOwnedOrder() throws Exception {
        when(dao.merchantOrderNo("trade-7")).thenReturn(order.getOrderSn());
        AlipayTradeQueryResponse response = new AlipayTradeQueryResponse(); response.setCode("10000");
        response.setOutTradeNo(order.getOrderSn()); response.setTradeNo("trade-7"); response.setTotalAmount("10.00"); response.setTradeStatus("TRADE_SUCCESS");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        assertEquals("TRADE_SUCCESS", service.query(null,"trade-7"));
        verify(states).applyPayment(any());
        assertThrows(ApiException.class, () -> service.query(null,"unbound-trade"));
    }
}
