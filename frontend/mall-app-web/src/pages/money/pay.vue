<template>
  <view class="app">
    <view class="price-box">
      <text>支付金额</text>
      <text class="price">{{ orderInfo.payAmount }}</text>
    </view>

    <view class="pay-type-list">
      <view class="type-item b-b" @click="handleChangePayType(1)">
        <text class="icon yticon icon-alipay"></text>
        <view class="con">
          <text class="tit">支付宝支付</text>
          <text>需配置真实或沙箱商户后使用</text>
        </view>
        <label class="radio">
          <radio name="payType" value="" color="#fa436a" :checked="payType == 1" />
        </label>
      </view>
      <view class="type-item b-b" @click="handleChangePayType(2)">
        <text class="icon yticon icon-weixinzhifu"></text>
        <view class="con">
          <text class="tit">微信支付（暂未接入）</text>
        </view>
        <label class="radio">
          <radio name="payType" value="" color="#fa436a" :checked="payType == 2" />
        </label>
      </view>
    </view>

    <text class="mix-btn" @click="handleConfirmPay">{{ USE_ALIPAY ? '前往支付宝' : '支付暂未接入' }}</text>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getOrderDetailAPI } from '@/apis/order'
import type { OmsOrderDetail } from '@/types/order'

// 是否使用支付宝支付（H5端启用，通过环境变量配置）
const USE_ALIPAY = import.meta.env.VITE_USE_ALIPAY === 'true'

// ===== 页面数据 =====
// 订单ID
const orderId = ref<number | null>(null)
// 支付方式：1->支付宝；2->微信
const payType = ref(1)
// 订单信息
const orderInfo = ref<Partial<OmsOrderDetail>>({})

// ===== 数据加载 =====
// 加载订单详情
const loadData = async () => {
  try {
    const res = await getOrderDetailAPI(orderId.value!)
    orderInfo.value = res.data
  } catch (e) {
    console.error('加载订单详情失败', e)
  }
}

// ===== 生命周期 =====
// 页面加载
onLoad(async (options: any) => {
  orderId.value = options.orderId
  loadData()
})

// ===== 事件处理方法 =====
// 选择支付方式
const handleChangePayType = (type: number) => {
  payType.value = type
}

// 确认支付
const handleConfirmPay = async () => {
  if (!USE_ALIPAY) {
    uni.showToast({ icon: 'none', title: '支付尚未接入，订单保持待付款状态' })
    return
  }
  if (payType.value !== 1 || !orderInfo.value.orderSn || orderInfo.value.status !== 0) {
    uni.showToast({ icon: 'none', title: '当前订单或支付方式不可支付' })
    return
  }
  // #ifdef H5
  try {
    const response = await fetch(import.meta.env.VITE_API_BASE_URL + '/alipay/webPay?outTradeNo=' + encodeURIComponent(orderInfo.value.orderSn), {
      headers: { Authorization: uni.getStorageSync('token') || '' },
    })
    const body = await response.text()
    if (!response.ok || !response.headers.get('content-type')?.includes('text/html')) {
      uni.showToast({ icon: 'none', title: '支付渠道未就绪，请稍后重试' })
      return
    }
    uni.setStorageSync('pendingPaymentOrder', { orderId: orderId.value, orderSn: orderInfo.value.orderSn })
    document.open()
    document.write(body)
    document.close()
  } catch {
    uni.showToast({ icon: 'none', title: '连接支付渠道失败，订单保持待付款状态' })
  }
  return
  // #endif
  // #ifndef H5
  uni.showToast({ icon: 'none', title: '当前平台尚未接入支付' })
  // #endif
}
</script>

<style lang="scss" scoped>
.app {
  width: 100%;
}

.price-box {
  background-color: #fff;
  height: 265rpx;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  font-size: 28rpx;
  color: #909399;

  .price {
    font-size: 50rpx;
    color: #303133;
    margin-top: 12rpx;

    &:before {
      content: '￥';
      font-size: 40rpx;
    }
  }
}

.pay-type-list {
  margin-top: 20rpx;
  background-color: #fff;
  padding-left: 60rpx;

  .type-item {
    height: 120rpx;
    padding: 20rpx 0;
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-right: 60rpx;
    font-size: 30rpx;
    position: relative;
  }

  .icon {
    width: 100rpx;
    font-size: 52rpx;
  }

  .icon-erjiye-yucunkuan {
    color: #fe8e2e;
  }

  .icon-weixinzhifu {
    color: #36cb59;
  }

  .icon-alipay {
    color: #01aaef;
  }

  .tit {
    font-size: $font-lg;
    color: $font-color-dark;
    margin-bottom: 4rpx;
  }

  .con {
    flex: 1;
    display: flex;
    flex-direction: column;
    font-size: $font-sm;
    color: $font-color-light;
  }
}

.mix-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 630rpx;
  height: 80rpx;
  margin: 80rpx auto 30rpx;
  font-size: $font-lg;
  color: #fff;
  background-color: $base-color;
  border-radius: 10rpx;
  box-shadow: 1px 2px 5px rgba(219, 63, 96, 0.4);
}
</style>
