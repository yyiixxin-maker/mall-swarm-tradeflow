import type { OrderParam, CalcAmount, UmsIntegrationConsumeSetting } from '@/types/order'

export const calculateIntegrationAmount = (integration: number, setting: UmsIntegrationConsumeSetting, couponAmount = 0) =>
  setting.useUnit > 0 && !(couponAmount > 0 && setting.couponStatus === 0) ? integration / setting.useUnit : 0

export const calculateCheckoutAmount = (
  amount: CalcAmount,
  couponAmount: number,
  integration: number,
  setting: UmsIntegrationConsumeSetting,
) => {
  const pointsAmount = calculateIntegrationAmount(integration, setting, couponAmount)
  return Number((amount.totalAmount + amount.freightAmount - amount.promotionAmount - couponAmount - pointsAmount).toFixed(2))
}

export const getCheckoutRequestKey = (data: Omit<OrderParam, 'requestKey'>): string => {
  const fingerprint = JSON.stringify({
    cartIds: [...data.cartIds].sort((a, b) => a - b),
    memberReceiveAddressId: data.memberReceiveAddressId,
    couponId: data.couponId ?? null,
    useIntegration: data.useIntegration,
    payType: data.payType,
  })
  const pending = uni.getStorageSync('pendingCheckout')
  if (pending?.fingerprint === fingerprint && /^[A-Za-z0-9_-]{8,64}$/.test(pending.requestKey)) {
    return pending.requestKey
  }
  const requestKey = globalThis.crypto?.randomUUID?.() ?? `checkout-${Date.now()}-${Math.random().toString(36).slice(2)}`
  uni.setStorageSync('pendingCheckout', { fingerprint, requestKey })
  return requestKey
}
