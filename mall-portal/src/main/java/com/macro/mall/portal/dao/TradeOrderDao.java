package com.macro.mall.portal.dao;

import com.macro.mall.model.*;
import com.macro.mall.portal.domain.*;
import org.apache.ibatis.annotations.*;
import java.util.Date;
import java.util.List;

/** Transactional writes live here rather than in generated mappers. */
public interface TradeOrderDao {
    @Update("UPDATE oms_cart_item SET quantity=quantity+#{quantity},modify_date=#{now} WHERE id=#{id} AND member_id=#{memberId} AND delete_status=0 AND quantity>0 AND #{quantity}>0 AND quantity<=2147483647-#{quantity}")
    int addCartQuantity(@Param("id") Long id,@Param("memberId") Long memberId,@Param("quantity") int quantity,@Param("now") Date now);
    @Insert("INSERT INTO order_request_dedup(member_id,request_key,request_hash) VALUES(#{memberId},#{key},#{hash}) ON DUPLICATE KEY UPDATE id=id")
    int claimRequest(@Param("memberId") Long memberId, @Param("key") String key, @Param("hash") String hash);

    @Select("SELECT request_hash AS requestHash,order_id AS orderId FROM order_request_dedup WHERE member_id=#{memberId} AND request_key=#{key} FOR UPDATE")
    OrderRequest lockRequest(@Param("memberId") Long memberId, @Param("key") String key);

    @Update("UPDATE order_request_dedup SET order_id=#{orderId} WHERE member_id=#{memberId} AND request_key=#{key} AND order_id IS NULL")
    int completeRequest(@Param("memberId") Long memberId, @Param("key") String key, @Param("orderId") Long orderId);

    @Select({"<script>SELECT id FROM oms_cart_item WHERE member_id=#{memberId} AND delete_status=0 AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id FOR UPDATE</script>"})
    List<Long> lockCart(@Param("memberId") Long memberId, @Param("ids") List<Long> ids);

    @Select("SELECT id,stock,lock_stock AS lockStock,product_id AS productId FROM pms_sku_stock WHERE id=#{id} FOR UPDATE")
    PmsSkuStock lockSku(Long id);

    @Update("UPDATE pms_sku_stock SET lock_stock=COALESCE(lock_stock,0)+#{quantity} WHERE id=#{id} AND #{quantity}>0 AND COALESCE(lock_stock,0)>=0 AND stock-COALESCE(lock_stock,0)>=#{quantity}")
    int reserve(@Param("id") Long id, @Param("quantity") int quantity);

    @Update("UPDATE pms_sku_stock SET stock=stock-#{quantity},lock_stock=lock_stock-#{quantity} WHERE id=#{id} AND #{quantity}>0 AND stock>=lock_stock AND lock_stock>=#{quantity}")
    int deduct(@Param("id") Long id, @Param("quantity") int quantity);

    @Update("UPDATE pms_sku_stock SET lock_stock=lock_stock-#{quantity} WHERE id=#{id} AND #{quantity}>0 AND stock>=lock_stock AND lock_stock>=#{quantity}")
    int release(@Param("id") Long id, @Param("quantity") int quantity);

    @Select("SELECT * FROM oms_order WHERE id=#{id} FOR UPDATE")
    @ResultMap("com.macro.mall.mapper.OmsOrderMapper.BaseResultMap")
    OmsOrder lockOrder(Long id);

    @Update("UPDATE oms_order SET status=#{status},pay_type=CASE WHEN #{status}=1 THEN 1 ELSE pay_type END,payment_time=CASE WHEN #{status}=1 THEN #{now} ELSE payment_time END,modify_time=#{now} WHERE id=#{id} AND status=0 AND delete_status=0")
    int transition(@Param("id") Long id, @Param("status") int status, @Param("now") Date now);

    @Update("UPDATE ums_member SET integration=COALESCE(integration,0)-#{points} WHERE id=#{id} AND #{points}>=0 AND COALESCE(integration,0)>=#{points}")
    int spendPoints(@Param("id") Long id, @Param("points") int points);

    @Update("UPDATE ums_member SET integration=COALESCE(integration,0)+#{points} WHERE id=#{id} AND #{points}>0")
    int refundPoints(@Param("id") Long id, @Param("points") int points);

    @Update("UPDATE sms_coupon_history SET use_status=1,use_time=#{now},order_id=#{orderId},order_sn=#{orderSn} WHERE id=#{id} AND member_id=#{memberId} AND use_status=0")
    int useCoupon(@Param("id") Long id,@Param("memberId") Long memberId,@Param("orderId") Long orderId,@Param("orderSn") String orderSn,@Param("now") Date now);

    @Update("UPDATE sms_coupon_history SET use_status=0,use_time=NULL,order_id=NULL,order_sn=NULL WHERE order_id=#{orderId} AND member_id=#{memberId} AND use_status=1")
    int refundCoupon(@Param("orderId") Long orderId,@Param("memberId") Long memberId);

    @Insert("INSERT INTO outbox_event(event_id,event_type,order_id,deadline,next_attempt) VALUES(#{eventId},'ORDER_TIMEOUT',#{orderId},#{deadline},#{now}) ON DUPLICATE KEY UPDATE event_id=event_id")
    int enqueue(@Param("eventId") String eventId,@Param("orderId") Long orderId,@Param("deadline") long deadline,@Param("now") long now);

    @Select("SELECT deadline FROM outbox_event WHERE order_id=#{orderId} AND event_type='ORDER_TIMEOUT'")
    Long deadline(Long orderId);

    @Select("SELECT e.order_id FROM outbox_event e JOIN oms_order o ON o.id=e.order_id WHERE e.deadline<=#{now} AND o.status=0 AND o.delete_status=0 ORDER BY e.deadline LIMIT 100")
    List<Long> overdue(long now);

    @Select("SELECT event_id AS eventId,order_id AS orderId,deadline,attempts FROM outbox_event WHERE published_at IS NULL AND next_attempt<=#{now} AND lease_until<=#{now} ORDER BY next_attempt LIMIT 20")
    List<OutboxEvent> pending(long now);

    @Update("UPDATE outbox_event SET lease_token=#{token},lease_until=#{until},attempts=attempts+1 WHERE event_id=#{id} AND published_at IS NULL AND lease_until<=#{now} AND next_attempt<=#{now}")
    int lease(@Param("id") String id,@Param("token") String token,@Param("now") long now,@Param("until") long until);

    @Update("UPDATE outbox_event SET published_at=#{now},lease_token=NULL,lease_until=0,last_error=NULL WHERE event_id=#{id} AND lease_token=#{token} AND published_at IS NULL")
    int published(@Param("id") String id,@Param("token") String token,@Param("now") long now);

    @Update("UPDATE outbox_event SET next_attempt=#{next},lease_token=NULL,lease_until=0,last_error=#{error} WHERE event_id=#{id} AND lease_token=#{token} AND published_at IS NULL")
    int retry(@Param("id") String id,@Param("token") String token,@Param("next") long next,@Param("error") String error);

    @Select("SELECT merchant_order_no AS merchantOrderNo,provider_trade_no AS providerTradeNo,order_id AS orderId,amount,app_id AS appId,seller_id AS sellerId,trade_status AS tradeStatus,state,review_reason AS reviewReason,verified_at AS verifiedAt FROM payment_transaction WHERE channel='ALIPAY' AND merchant_order_no=#{sn} FOR UPDATE")
    PaymentTransaction payment(String sn);

    @Select("SELECT merchant_order_no FROM payment_transaction WHERE channel='ALIPAY' AND provider_trade_no=#{tradeNo}")
    String merchantOrderNo(String tradeNo);

    @Insert("INSERT INTO payment_transaction(channel,merchant_order_no,provider_trade_no,order_id,amount,app_id,seller_id,trade_status,state,review_reason,verified_at) VALUES('ALIPAY',#{merchantOrderNo},#{providerTradeNo},#{orderId},#{amount},#{appId},#{sellerId},#{tradeStatus},#{state},#{reviewReason},#{verifiedAt})")
    int insertPayment(PaymentTransaction payment);
}
