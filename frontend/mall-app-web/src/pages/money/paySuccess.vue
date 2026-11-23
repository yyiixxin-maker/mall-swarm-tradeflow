<template>
  <view class="content">
    <text v-if="paymentVerified" class="success-icon yticon icon-xuanzhong2"></text>
    <text class="tit">{{ payText }}</text>

    <view class="btn-group">
      <navigator url="/pages/order/order?state=0" open-type="redirect" class="mix-btn">
        查看订单
      </navigator>
      <navigator url="/pages/index/index" open-type="switchTab" class="mix-btn hollow">
        返回首页
      </navigator>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { fetchAliapyStatusAPI, getOrderDetailAPI } from '@/apis/order'

// 是否使用支付宝支付（H5端启用，通过环境变量配置）
const USE_ALIPAY = import.meta.env.VITE_USE_ALIPAY === 'true'

// ===== 页面数据 =====
// 支付结果文字
const payText = ref('正在核验支付结果')
const paymentVerified = ref(false)

// ===== 数据加载 =====
// 查询支付宝交易状态
const loadData = async (outTradeNo: string) => {
  const pending = uni.getStorageSync('pendingPaymentOrder')
  if (!pending?.orderId || pending.orderSn !== outTradeNo) {
    payText.value = '请在订单中核对支付结果'
    return
  }
  try {
    await fetchAliapyStatusAPI({ outTradeNo })
    const response = await getOrderDetailAPI(pending.orderId)
    if (response.data.orderSn === outTradeNo && [1, 2, 3].includes(response.data.status)) {
      paymentVerified.value = true
      payText.value = '支付已确认'
      uni.removeStorageSync('pendingPaymentOrder')
    } else {
      payText.value = '支付尚未入账或需要核对，请查看订单'
    }
  } catch {
    payText.value = '暂时无法确认支付，请稍后查看订单'
  }
}

onLoad((options: any) => {
  if (!USE_ALIPAY) {
    payText.value = '支付尚未接入，订单状态以服务端为准'
    return
  }
  if (options.out_trade_no) loadData(String(options.out_trade_no))
  else payText.value = '请在订单中核对支付结果'
})
</script>

<style lang="scss" scoped>
.content {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.success-icon {
  font-size: 160rpx;
  color: #fa436a;
  margin-top: 100rpx;
}

.tit {
  font-size: 38rpx;
  color: #303133;
}

.btn-group {
  padding-top: 100rpx;
}

.mix-btn {
  margin-top: 30rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 600rpx;
  height: 80rpx;
  font-size: $font-lg;
  color: #fff;
  background-color: $base-color;
  border-radius: 10rpx;

  &.hollow {
    background: #fff;
    color: #303133;
    border: 1px solid #ccc;
  }
}
</style>
