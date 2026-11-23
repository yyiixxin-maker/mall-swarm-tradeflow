package com.macro.mall;

import com.macro.mall.common.exception.ApiException;
import com.macro.mall.dto.PmsProductParam;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.service.*;
import com.macro.mall.service.impl.*;
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
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(SkuDatabaseReliabilityTests.Config.class)
@EnabledIfEnvironmentVariable(named="TRADEFLOW_TEST_JDBC_URL",matches=".*?/tradeflow_test_[a-f0-9]{12}\\?.*")
class SkuDatabaseReliabilityTests {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    @MapperScan({"com.macro.mall.mapper","com.macro.mall.dao"})
    @Import({PmsSkuStockServiceImpl.class,PmsProductServiceImpl.class,OmsOrderServiceImpl.class})
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(System.getenv("TRADEFLOW_TEST_JDBC_URL"),"mall",System.getenv("TRADEFLOW_MYSQL_PASSWORD")); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(source);
            PathMatchingResourcePatternResolver resolver=new PathMatchingResourcePatternResolver();
            List<org.springframework.core.io.Resource> resources=new ArrayList<>(Arrays.asList(resolver.getResources("classpath*:com/**/mapper/*.xml")));
            resources.addAll(Arrays.asList(resolver.getResources("classpath*:dao/*.xml")));
            factory.setMapperLocations(resources.toArray(new org.springframework.core.io.Resource[0]));return factory.getObject();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean UmsAdminService adminService() { return org.mockito.Mockito.mock(UmsAdminService.class); }
        @Bean PortalOrderClient portalOrderClient() { return org.mockito.Mockito.mock(PortalOrderClient.class); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PmsSkuStockService skus;
    @Autowired PmsProductService products;
    @Autowired OmsOrderService orders;
    @BeforeEach void fixture() {
        for(String table:List.of("oms_order_operate_history","oms_order_item","oms_order","pms_sku_stock","pms_product"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO pms_product(id,name,product_sn) VALUES(1,'fixture','fixture-1'),(2,'foreign-fixture','fixture-2')");
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price,promotion_price,stock,lock_stock) VALUES(1,1,'fixture-1',100,80,20,4),(2,1,'fixture-2',100,80,20,2),(3,2,'fixture-3',100,80,20,0)");
    }
    private PmsSkuStock sku(long id,int stock) { PmsSkuStock sku=new PmsSkuStock();sku.setId(id);sku.setStock(stock);return sku; }
    private int number(String sql) { return jdbc.queryForObject(sql,Integer.class); }

    @Test void stockEditorPreservesReservationsAndPromotionPriceAndCannotChangeProduct() {
        PmsSkuStock sku=sku(1,30);sku.setLockStock(0);sku.setProductId(2L);
        assertEquals(1,skus.update(1L,List.of(sku)));
        assertEquals(4,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(80,number("SELECT promotion_price FROM pms_sku_stock WHERE id=1"));
        assertEquals(1,number("SELECT product_id FROM pms_sku_stock WHERE id=1"));
    }

    @Test void invalidSecondSkuRollsBackFirstAndForeignSkuIsRejected() {
        assertThrows(ApiException.class,()->skus.update(1L,List.of(sku(1,30),sku(2,1))));
        assertEquals(20,number("SELECT stock FROM pms_sku_stock WHERE id=1"));
        assertThrows(ApiException.class,()->skus.update(1L,List.of(sku(3,30))));
        assertThrows(ApiException.class,()->skus.update(1L,List.of(sku(1,-1))));
    }

    @Test void productEditorAlsoIgnoresSubmittedLockStock() {
        PmsProductParam param=new PmsProductParam();param.setName("changed");
        PmsSkuStock first=sku(1,30);first.setLockStock(0);
        param.setSkuStockList(List.of(first,sku(2,30)));
        products.update(1L,param);
        assertEquals(4,number("SELECT lock_stock FROM pms_sku_stock WHERE id=1"));
        assertEquals(80,number("SELECT promotion_price FROM pms_sku_stock WHERE id=1"));
    }

    @Test void productEditorCannotDeleteReservedSkuAndRollsBackProductChange() {
        PmsProductParam param=new PmsProductParam();param.setName("must-rollback");param.setSkuStockList(Collections.emptyList());
        assertThrows(ApiException.class,()->products.update(1L,param));
        assertEquals("fixture",jdbc.queryForObject("SELECT name FROM pms_product WHERE id=1",String.class));
        assertEquals(2,number("SELECT COUNT(*) FROM pms_sku_stock WHERE product_id=1"));
    }

    @Test void paidOrShippedUnfinishedOrderPreventsDeletingSkuEvenWithoutLocks() {
        jdbc.update("UPDATE pms_sku_stock SET lock_stock=0");
        for(int status:List.of(0,1,2)) {
            jdbc.update("DELETE FROM oms_order");
            jdbc.update("INSERT INTO oms_order(id,member_id,receiver_name,receiver_phone,order_sn,status,delete_status) VALUES(1,1,'fixture','000','sku-active-order',?,0)",status);
            jdbc.update("DELETE FROM oms_order_item");
            jdbc.update("INSERT INTO oms_order_item(order_id,product_sku_id,product_quantity) VALUES(1,1,1)");
            assertThrows(ApiException.class,()->skus.synchronize(1L,Collections.emptyList()));
            assertEquals(2,number("SELECT COUNT(*) FROM pms_sku_stock WHERE product_id=1"));
        }
        jdbc.update("UPDATE oms_order SET status=3");
        skus.synchronize(1L,Collections.emptyList());
        assertEquals(0,number("SELECT COUNT(*) FROM pms_sku_stock WHERE product_id=1"));
    }

    @Test void newSkuStartsWithoutReservationsAndPreservesPromotionPrice() {
        PmsSkuStock created=new PmsSkuStock();created.setStock(10);created.setPrice(new BigDecimal("100"));
        created.setPromotionPrice(new BigDecimal("70"));created.setLockStock(99);created.setProductId(2L);created.setSkuCode("fixture-new");
        skus.synchronize(1L,List.of(sku(1,20),sku(2,20),created));
        assertEquals(1,number("SELECT COUNT(*) FROM pms_sku_stock WHERE product_id=1 AND lock_stock=0 AND promotion_price=70"));
    }

    @Test void adminDeleteCannotHideAnOrderThatStillOwnsResources() {
        for(int status=0;status<=4;status++)jdbc.update("INSERT INTO oms_order(id,member_id,receiver_name,receiver_phone,order_sn,status,delete_status) VALUES(?,1,'fixture','000',?,?,0)",status+1,"fixture-order-"+status,status);
        assertEquals(2,orders.delete(List.of(1L,2L,3L,4L,5L)));
        assertEquals(0,number("SELECT COUNT(*) FROM oms_order WHERE status IN(0,1,2) AND delete_status=1"));
    }

    @Test void mixedDeliveryStatusesRollbackTheBatchWithoutPhantomHistory() {
        for(int status=0;status<=1;status++)jdbc.update("INSERT INTO oms_order(id,member_id,receiver_name,receiver_phone,order_sn,status,delete_status) VALUES(?,1,'fixture','000',?,?,0)",status+1,"fixture-delivery-"+status,status);
        List<com.macro.mall.dto.OmsOrderDeliveryParam> params=new ArrayList<>();
        for(long id:List.of(1L,2L)) {
            var param=new com.macro.mall.dto.OmsOrderDeliveryParam();param.setOrderId(id);param.setDeliveryCompany("fixture");param.setDeliverySn("fixture");params.add(param);
        }
        assertThrows(ApiException.class,()->orders.delivery(params));
        assertEquals(1,number("SELECT status FROM oms_order WHERE id=2"));
        assertEquals(0,number("SELECT COUNT(*) FROM oms_order_operate_history"));
    }
}
