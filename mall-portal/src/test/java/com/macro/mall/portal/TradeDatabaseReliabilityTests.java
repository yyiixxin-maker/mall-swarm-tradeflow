package com.macro.mall.portal;

import com.alipay.api.AlipayClient;
import com.macro.mall.common.domain.OrderCloseRequest;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.mapper.*;
import com.macro.mall.model.*;
import com.macro.mall.portal.config.AlipayConfig;
import com.macro.mall.portal.dao.TradeOrderDao;
import com.macro.mall.portal.domain.*;
import com.macro.mall.portal.service.*;
import com.macro.mall.portal.service.impl.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(TradeDatabaseReliabilityTests.Config.class)
@EnabledIfEnvironmentVariable(named="TRADEFLOW_TEST_JDBC_URL", matches=".*?/tradeflow_test_[a-f0-9]{12}\\?.*")
class TradeDatabaseReliabilityTests {
    private static final ThreadLocal<Long> MEMBER = ThreadLocal.withInitial(() -> 1L);
    @Configuration
    @EnableTransactionManagement(proxyTargetClass=true)
    @MapperScan({"com.macro.mall.mapper","com.macro.mall.portal.dao"})
    @Import({OmsPortalOrderServiceImpl.class, OrderStateService.class, OmsCartItemServiceImpl.class,
            OmsPromotionServiceImpl.class,UmsMemberCouponServiceImpl.class,UmsMemberReceiveAddressServiceImpl.class,AlipayServiceImpl.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("TRADEFLOW_TEST_JDBC_URL"),"mall",System.getenv("TRADEFLOW_MYSQL_PASSWORD"));
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean(); factory.setDataSource(dataSource);
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            List<org.springframework.core.io.Resource> resources = new ArrayList<>(Arrays.asList(resolver.getResources("classpath*:com/**/mapper/*.xml")));
            resources.addAll(Arrays.asList(resolver.getResources("classpath*:dao/*.xml")));
            factory.setMapperLocations(resources.toArray(new org.springframework.core.io.Resource[0]));
            return factory.getObject();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) { return new DataSourceTransactionManager(dataSource); }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean UmsMemberCacheService memberCache() { return mock(UmsMemberCacheService.class); }
        @Bean AlipayClient alipayClient() { return mock(AlipayClient.class); }
        @Bean AlipayConfig alipayConfig() { return PaymentTestSupport.config(); }
        @Bean UmsMemberService memberService(UmsMemberMapper mapper) {
            UmsMemberService service = mock(UmsMemberService.class);
            when(service.getCurrentMember()).thenAnswer(i -> mapper.selectByPrimaryKey(MEMBER.get()));
            when(service.getById(anyLong())).thenAnswer(i -> mapper.selectByPrimaryKey(i.getArgument(0)));
            return service;
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OmsPortalOrderService orders;
    @Autowired OrderStateService states;
    @Autowired TradeOrderDao dao;
    @Autowired OmsCartItemService carts;
    @Autowired AlipayService alipay;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach void fixture() {
        MEMBER.set(1L);
        for (String table : List.of("payment_transaction","outbox_event","order_request_dedup","oms_order_operate_history","oms_order_item","oms_order","oms_cart_item","sms_coupon_history","sms_coupon","ums_member_receive_address","ums_member","pms_sku_stock","pms_product","oms_order_setting","ums_integration_consume_setting")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO ums_member(id,username,integration,status) VALUES(1,'fixture-one',1000,1),(2,'fixture-two',1000,1)");
        jdbc.update("INSERT INTO ums_member_receive_address(id,member_id,name,phone_number,detail_address) VALUES(1,1,'fixture','00000000000','fixture'),(2,2,'fixture','00000000000','fixture')");
        jdbc.update("INSERT INTO pms_product(id,name,product_sn,delete_status,publish_status,promotion_type,gift_point,gift_growth) VALUES(1,'fixture','fixture-1',0,1,0,7,3),(2,'fixture-two','fixture-2',0,1,0,7,3)");
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price,stock,lock_stock) VALUES(1,1,'fixture-1',10,20,0),(2,2,'fixture-2',10,0,0)");
        jdbc.update("INSERT INTO oms_order_setting(id,normal_order_overtime,confirm_overtime) VALUES(1,60,7)");
        jdbc.update("INSERT INTO ums_integration_consume_setting(id,use_unit,coupon_status,max_percent_per_order) VALUES(1,100,1,50)");
        cart(1,1,1);
    }

    private void cart(long id, int quantity, long sku) {
        jdbc.update("INSERT INTO oms_cart_item(id,member_id,product_id,product_sku_id,quantity,price,delete_status) VALUES(?,1,?,?,?,0.01,0)",id,sku,sku,quantity);
    }
    private OrderParam param(String key, Long... ids) {
        OrderParam param = new OrderParam(); param.setRequestKey(key); param.setCartIds(Arrays.asList(ids));
        param.setMemberReceiveAddressId(1L); param.setPayType(1); param.setUseIntegration(0); return param;
    }
    private OmsOrder create(OrderParam param) { return (OmsOrder) orders.generateOrder(param).get("order"); }
    private int number(String query) { return jdbc.queryForObject(query,Integer.class); }
    private List<Throwable> concurrently(int count, IntFunction<Runnable> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        try {
            for (int i=0;i<count;i++) {
                int index=i;
                futures.add(pool.submit(() -> {
                    MEMBER.set(1L); ready.countDown(); start.await();
                    try { work.apply(index).run(); return null; } catch (Throwable e) { return e; }
                    finally { MEMBER.remove(); }
                }));
            }
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            List<Throwable> errors = new ArrayList<>();
            for (Future<Throwable> future : futures) errors.add(future.get(30,TimeUnit.SECONDS));
            return errors;
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentLastUnitHasExactlyOneWinner() throws Exception {
        jdbc.update("UPDATE pms_sku_stock SET stock=1 WHERE id=1");
        for(int i=2;i<=8;i++) cart(i,1,1);
        List<Throwable> errors = concurrently(8, i -> () -> create(param("last-unit-"+i,(long)i+1)));
        assertEquals(1,errors.stream().filter(Objects::isNull).count());
        assertTrue(errors.stream().filter(Objects::nonNull).allMatch(e -> e instanceof ApiException),errors.toString());
        assertEquals(1,number("SELECT COUNT(*) FROM oms_order"));
        assertEquals(1,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(1,number("SELECT COUNT(*) FROM outbox_event"));
    }

    @Test void concurrentSameRequestReturnsOneOrderEvenAfterCartDeletion() throws Exception {
        List<Long> ids = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = concurrently(6, i -> () -> ids.add(create(param("same-request",1L)).getId()));
        assertTrue(errors.stream().allMatch(Objects::isNull),errors.toString());
        assertEquals(1,new HashSet<>(ids).size());
        assertEquals(1,number("SELECT COUNT(*) FROM oms_order"));
        assertEquals(1,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        cart(2,1,1);
        assertThrows(ApiException.class, () -> create(param("same-request",2L)));
    }

    @Test void differentKeysCannotCheckoutTheSameCartTwice() throws Exception {
        List<Throwable> errors = concurrently(2, i -> () -> create(param("different-key-"+i,1L)));
        assertEquals(1,errors.stream().filter(Objects::isNull).count());
        assertEquals(1,number("SELECT COUNT(*) FROM order_request_dedup"));
    }

    @Test void duplicateSkuQuantitiesAreAggregatedAndMultiSkuFailureRollsBack() {
        cart(2,2,1);
        OmsOrder order = create(param("aggregate-sku",1L,2L));
        assertEquals(3,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(0,new BigDecimal("30.00").compareTo(order.getPayAmount()));
        orders.cancelUserOrder(order.getId());
        cart(3,1,1); cart(4,1,2);
        assertThrows(ApiException.class, () -> create(param("multi-rollback",3L,4L)));
        assertEquals(0,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(1,number("SELECT COUNT(*) FROM oms_order"));
        assertEquals(0,number("SELECT COUNT(*) FROM order_request_dedup WHERE request_key='multi-rollback'"));
    }

    @Test void pointsAndBoundCouponAreRefundedOnceAndGiftPointsKeepTheirMeaning() {
        jdbc.update("INSERT INTO sms_coupon(id,amount,min_point,use_type,start_time,end_time) VALUES(1,1,0,0,'2020-01-01','2099-01-01')");
        jdbc.update("INSERT INTO sms_coupon_history(id,coupon_id,member_id,use_status) VALUES(1,1,1,0),(2,1,1,0)");
        OrderParam param = param("points-refund",1L); param.setUseIntegration(100); param.setCouponId(1L);
        OmsOrder order = create(param);
        assertEquals(100,number("SELECT use_integration FROM oms_order"));
        assertEquals(7,number("SELECT integration FROM oms_order"));
        assertEquals(900,number("SELECT integration FROM ums_member WHERE id=1"));
        assertEquals(1,number("SELECT COUNT(*) FROM sms_coupon_history WHERE order_id="+order.getId()));
        orders.cancelUserOrder(order.getId()); orders.cancelUserOrder(order.getId());
        assertEquals(1000,number("SELECT integration FROM ums_member WHERE id=1"));
        assertEquals(2,number("SELECT COUNT(*) FROM sms_coupon_history WHERE use_status=0 AND order_id IS NULL"));
        assertEquals(1,number("SELECT COUNT(*) FROM oms_order_operate_history WHERE order_status=4"));
    }

    @Test void paymentAndCancellationRaceHasOneWinnerAndLateMoneyIsDurable() throws Exception {
        for (int round=0;round<8;round++) {
            long cartId=round+10; cart(cartId,1,1);
            OmsOrder order=create(param("pay-cancel-"+round,cartId));
            Map<String,String> notification=PaymentTestSupport.signed(order.getOrderSn(),"race-trade-"+round,"10.00");
            List<Throwable> errors=concurrently(2,i -> i==0 ? () -> assertEquals("success",alipay.notify(notification)) : () -> orders.cancelUserOrder(order.getId()));
            assertTrue(errors.stream().filter(Objects::nonNull).allMatch(e -> e instanceof ApiException),errors.toString());
            int status=number("SELECT status FROM oms_order WHERE id="+order.getId());
            assertTrue(status==1 || status==4);
            assertEquals(status==1 ? "APPLIED" : "REVIEW",dao.payment(order.getOrderSn()).getState());
            assertEquals("success",alipay.notify(notification));
            assertEquals(0,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
            int paid=number("SELECT COUNT(*) FROM oms_order WHERE status=1");
            assertEquals(20-paid,number("SELECT stock FROM pms_sku_stock WHERE id=1"));
        }
    }

    @Test void duplicateVerifiedCallbacksDeductOnlyOnce() throws Exception {
        OmsOrder order=create(param("callback-duplicate",1L));
        Map<String,String> notification=PaymentTestSupport.signed(order.getOrderSn(),"duplicate-trade","10.00");
        List<Throwable> errors=concurrently(6,i -> () -> assertEquals("success",alipay.notify(notification)));
        assertTrue(errors.stream().allMatch(Objects::isNull),errors.toString());
        assertEquals(19,number("SELECT stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(1,number("SELECT COUNT(*) FROM payment_transaction"));
        assertEquals(1,number("SELECT COUNT(*) FROM oms_order_operate_history WHERE order_status=1"));
    }

    @Test void amountMismatchAndClosedOrderStayUnpaidButRetainVerifiedFacts() throws Exception {
        OmsOrder order=create(param("amount-mismatch",1L));
        assertEquals("success",alipay.notify(PaymentTestSupport.signed(order.getOrderSn(),"mismatch-trade","0.01")));
        assertEquals("AMOUNT_MISMATCH",dao.payment(order.getOrderSn()).getReviewReason());
        assertEquals(0,number("SELECT status FROM oms_order"));
        assertThrows(ApiException.class,()->orders.cancelUserOrder(order.getId()));
        assertEquals(20,number("SELECT stock FROM pms_sku_stock WHERE id=1"));
    }

    @Test void earlyAndDuplicateTimeoutCannotCancelPaidOrder() throws Exception {
        OmsOrder order=create(param("timeout-check",1L));
        orders.cancelOrder(order.getId()); assertEquals(0,number("SELECT status FROM oms_order"));
        jdbc.update("UPDATE outbox_event SET deadline=?",System.currentTimeMillis()-1000);
        orders.cancelOrder(order.getId()); orders.cancelOrder(order.getId());
        assertEquals(4,number("SELECT status FROM oms_order"));
        assertEquals(0,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals("success",alipay.notify(PaymentTestSupport.signed(order.getOrderSn(),"late-trade","10.00")));
        assertEquals("REVIEW",dao.payment(order.getOrderSn()).getState());
        assertEquals(4,number("SELECT status FROM oms_order"));
        orders.deleteOrder(order.getId());
        assertDoesNotThrow(()->orders.cancelOrder(order.getId()));
    }

    @Test void timeoutSweepClosesMissedDeliveryButDoesNotTouchHistoricalSeedOrders() {
        OmsOrder order=create(param("sweep-fallback",1L));
        jdbc.update("UPDATE outbox_event SET deadline=?",System.currentTimeMillis()-1000);
        jdbc.update("INSERT INTO oms_order(id,member_id,receiver_name,receiver_phone,order_sn,status,delete_status,create_time) VALUES(99999,1,'fixture','000','historical-fixture',0,0,'2018-01-01')");
        assertEquals(1,orders.cancelTimeOutOrder());
        assertEquals(4,number("SELECT status FROM oms_order WHERE id="+order.getId()));
        assertEquals(0,number("SELECT status FROM oms_order WHERE id=99999"));
    }

    @Test void ownerChecksAndInputValidationHaveNoPartialEffects() {
        OmsOrder order=create(param("ownership-check",1L));
        MEMBER.set(2L);
        assertThrows(ApiException.class,()->orders.detail(order.getId()));
        assertThrows(ApiException.class,()->orders.cancelUserOrder(order.getId()));
        assertThrows(ApiException.class,()->orders.sendDelayMessageCancelOrder(order.getId()));
        assertThrows(ApiException.class,()->orders.paySuccess(order.getId(),1));
        MEMBER.set(1L);
        cart(2,-1,1);
        assertThrows(ApiException.class,()->create(param("negative-quantity",2L)));
        assertThrows(ApiException.class,()->create(param("duplicate-cart",1L,1L)));
        OrderParam empty=param("empty-cart"); assertThrows(ApiException.class,()->create(empty));
        OrderParam bad=param("bad-pay-type",2L); bad.setPayType(99); assertThrows(ApiException.class,()->create(bad));
        assertEquals(1,number("SELECT COUNT(*) FROM oms_order"));
        assertEquals(1,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
    }

    @Test void clientDeclaredPaymentCannotAdvanceAnUnverifiedOrder() {
        OmsOrder order=create(param("forged-pay-success",1L));
        assertThrows(ApiException.class,()->orders.paySuccess(order.getId(),1));
        assertThrows(ApiException.class,()->orders.paySuccess(order.getId(),2));
        assertEquals(0,number("SELECT status FROM oms_order"));
        assertEquals(20,number("SELECT stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(1,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(0,number("SELECT COUNT(*) FROM payment_transaction"));
    }

    @Test void cartWritesRejectInvalidQuantitiesAndCannotChangeAnotherMembersEntry() {
        OmsCartItem cart=new OmsCartItem();cart.setProductId(1L);cart.setProductSkuId(1L);cart.setQuantity(-1);
        assertThrows(ApiException.class,()->carts.add(cart));
        assertThrows(ApiException.class,()->carts.updateQuantity(1L,1L,0));
        cart.setId(1L);cart.setQuantity(1);MEMBER.set(2L);
        assertThrows(ApiException.class,()->carts.updateAttr(cart));
        MEMBER.set(1L);
        assertEquals(0,number("SELECT delete_status FROM oms_cart_item WHERE id=1"));
        cart.setId(null);cart.setProductId(2L);cart.setProductSkuId(1L);
        assertThrows(ApiException.class,()->carts.add(cart));
    }

    @Test void adminCloseUsesSameResourceTransactionAndWritesOnlyWinningHistory() {
        OmsOrder order=create(param("admin-close",1L));
        OrderCloseRequest request=new OrderCloseRequest(); request.setIds(List.of(order.getId()));request.setOperator("fixture-admin");request.setNote("fixture");
        assertEquals(1,states.close(request)); assertEquals(0,states.close(request));
        assertEquals(0,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(1,number("SELECT COUNT(*) FROM oms_order_operate_history WHERE operate_man='fixture-admin' AND order_status=4"));
    }

    @Test void paidInventoryMismatchIsRecordedForReviewWithoutPartialDeduction() throws Exception {
        OmsOrder order=create(param("inventory-review",1L));
        jdbc.update("UPDATE pms_sku_stock SET lock_stock=0 WHERE id=1");
        assertEquals("success",alipay.notify(PaymentTestSupport.signed(order.getOrderSn(),"review-trade","10.00")));
        assertEquals("RESERVATION_MISMATCH",dao.payment(order.getOrderSn()).getReviewReason());
        assertEquals(20,number("SELECT stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(0,number("SELECT status FROM oms_order"));
    }

    @Test void transactionFailureRollsBackOrderInventoryDedupAndOutboxTogether() {
        org.springframework.transaction.support.TransactionTemplate transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            create(param("rollback-outbox",1L));
            throw new IllegalStateException("simulated crash before commit");
        }));
        assertEquals(0,number("SELECT COUNT(*) FROM oms_order"));
        assertEquals(0,number("SELECT COUNT(*) FROM oms_order_item"));
        assertEquals(0,number("SELECT COUNT(*) FROM order_request_dedup"));
        assertEquals(0,number("SELECT COUNT(*) FROM outbox_event"));
        assertEquals(0,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(0,number("SELECT delete_status FROM oms_cart_item WHERE id=1"));
    }

    @Test void competingPublishersCannotShareALeaseAndExpiredLeaseCanRecover() throws Exception {
        create(param("lease-recovery",1L));
        OutboxEvent event=dao.pending(System.currentTimeMillis()).get(0);
        List<Integer> claimed=Collections.synchronizedList(new ArrayList<>());
        long now=System.currentTimeMillis();
        List<Throwable> errors=concurrently(6,i -> () -> claimed.add(dao.lease(event.getEventId(),"owner-"+i,now,now+30_000)));
        assertTrue(errors.stream().allMatch(Objects::isNull));
        assertEquals(1,claimed.stream().mapToInt(Integer::intValue).sum());
        assertEquals(0,dao.published(event.getEventId(),"wrong-owner",now));
        jdbc.update("UPDATE outbox_event SET lease_until=?",now-1);
        assertEquals(1,dao.lease(event.getEventId(),"new-owner",now,now+30_000));
        assertEquals(1,dao.published(event.getEventId(),"new-owner",now));
        assertTrue(dao.pending(now).isEmpty());
    }
}
