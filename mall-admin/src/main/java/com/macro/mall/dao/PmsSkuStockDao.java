package com.macro.mall.dao;

import com.macro.mall.model.PmsSkuStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

/**
 * 自定义商品SKU管理Dao
 * Created by macro on 2018/4/26.
 */
public interface PmsSkuStockDao {
    /**
     * 批量插入操作
     */
    int insertList(@Param("list")List<PmsSkuStock> skuStockList);

    @Select("SELECT id FROM pms_sku_stock WHERE id=#{id} AND product_id=#{pid} FOR UPDATE")
    Long lock(@Param("pid") Long pid,@Param("id") Long id);

    @Update("UPDATE pms_sku_stock SET sku_code=COALESCE(#{sku.skuCode},sku_code),price=COALESCE(#{sku.price},price),stock=COALESCE(#{sku.stock},stock),low_stock=COALESCE(#{sku.lowStock},low_stock),pic=COALESCE(#{sku.pic},pic),sp_data=COALESCE(#{sku.spData},sp_data),promotion_price=COALESCE(#{sku.promotionPrice},promotion_price) WHERE id=#{sku.id} AND product_id=#{pid} AND COALESCE(#{sku.stock},stock)>=COALESCE(lock_stock,0)")
    int update(@Param("pid") Long pid,@Param("sku") PmsSkuStock sku);

    @Delete("DELETE FROM pms_sku_stock WHERE id=#{id} AND product_id=#{pid} AND COALESCE(lock_stock,0)=0 AND NOT EXISTS(SELECT 1 FROM oms_order_item i JOIN oms_order o ON o.id=i.order_id WHERE i.product_sku_id=#{id} AND o.status IN(0,1,2))")
    int deleteUnused(@Param("pid") Long pid,@Param("id") Long id);
}
