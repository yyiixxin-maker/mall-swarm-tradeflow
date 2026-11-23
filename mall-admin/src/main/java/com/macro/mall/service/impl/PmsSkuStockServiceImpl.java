package com.macro.mall.service.impl;

import com.macro.mall.dao.PmsSkuStockDao;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.model.PmsSkuStockExample;
import com.macro.mall.service.PmsSkuStockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import com.macro.mall.common.exception.Asserts;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品sku库存管理Service实现类
 * Created by macro on 2018/4/27.
 */
@Service
public class PmsSkuStockServiceImpl implements PmsSkuStockService {
    @Autowired
    private PmsSkuStockMapper skuStockMapper;
    @Autowired
    private PmsSkuStockDao skuStockDao;

    @Override
    public List<PmsSkuStock> getList(Long pid, String keyword) {
        PmsSkuStockExample example = new PmsSkuStockExample();
        PmsSkuStockExample.Criteria criteria = example.createCriteria().andProductIdEqualTo(pid);
        if (!StringUtils.isEmpty(keyword)) {
            criteria.andSkuCodeLike("%" + keyword + "%");
        }
        return skuStockMapper.selectByExample(example);
    }

    @Override
    @Transactional
    public int update(Long pid, List<PmsSkuStock> skuStockList) {
        if (skuStockList == null || skuStockList.isEmpty()) Asserts.fail("SKU列表不能为空");
        List<PmsSkuStock> ordered = new ArrayList<>(skuStockList);
        Set<Long> seen = new HashSet<>();
        for (PmsSkuStock sku : ordered) {
            validate(sku);
            if (sku.getId() == null || !seen.add(sku.getId())) Asserts.fail("SKU ID无效或重复");
        }
        ordered.sort(Comparator.comparing(PmsSkuStock::getId));
        for (PmsSkuStock sku : ordered) {
            if (skuStockDao.lock(pid, sku.getId()) == null) Asserts.fail("SKU不属于此商品");
            if (skuStockDao.update(pid, sku) != 1) Asserts.fail("库存不能低于已预留数量");
        }
        return ordered.size();
    }

    @Override
    @Transactional
    public void synchronize(Long pid, List<PmsSkuStock> skus) {
        List<PmsSkuStock> requested = skus == null ? Collections.emptyList() : skus;
        List<PmsSkuStock> original = getList(pid, null);
        SortedSet<Long> ids = new TreeSet<>();
        for (PmsSkuStock sku : original) ids.add(sku.getId());
        Set<Long> kept = new HashSet<>();
        for (PmsSkuStock sku : requested) {
            validate(sku);
            if (sku.getId() != null && (!ids.contains(sku.getId()) || !kept.add(sku.getId()))) Asserts.fail("SKU不属于此商品或重复");
        }
        for (Long id : ids) skuStockDao.lock(pid, id);
        for (Long id : ids) {
            if (!kept.contains(id) && skuStockDao.deleteUnused(pid, id) != 1) Asserts.fail("存在预留库存或未完成订单，不能删除SKU");
        }
        for (PmsSkuStock sku : requested) {
            if (sku.getId() == null) {
                if (sku.getPrice() == null || sku.getStock() == null) Asserts.fail("新增SKU必须提供价格和库存");
                sku.setProductId(pid);
                sku.setLockStock(0);
                skuStockDao.insertList(Collections.singletonList(sku));
            } else if (skuStockDao.update(pid, sku) != 1) Asserts.fail("库存不能低于已预留数量");
        }
    }

    public static void validate(PmsSkuStock sku) {
        if (sku == null || (sku.getStock() != null && sku.getStock() < 0)
                || (sku.getPrice() != null && sku.getPrice().signum() <= 0)
                || (sku.getPromotionPrice() != null && sku.getPromotionPrice().signum() < 0)) Asserts.fail("SKU价格或库存无效");
    }
}
