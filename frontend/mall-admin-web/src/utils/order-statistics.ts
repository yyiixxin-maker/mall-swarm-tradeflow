type OrderSample = { createTime?: string; status?: number; payAmount?: number }

export function groupOrderSample(orders: OrderSample[], range: [string, string] | null = null) {
  const groups = new Map<string, { date: string; orderCount: number; paidAmount: number }>()
  for (const order of orders) {
    const date = order.createTime?.slice(0, 10)
    if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(Date.parse(date))) continue
    if (range && (date < range[0] || date > range[1])) continue
    const group = groups.get(date) ?? { date, orderCount: 0, paidAmount: 0 }
    group.orderCount++
    if ([1, 2, 3].includes(order.status ?? -1) && typeof order.payAmount === 'number' && Number.isFinite(order.payAmount)) {
      group.paidAmount = (Math.round(group.paidAmount * 100) + Math.round(order.payAmount * 100)) / 100
    }
    groups.set(date, group)
  }
  return [...groups.values()].sort((a, b) => a.date.localeCompare(b.date))
}
