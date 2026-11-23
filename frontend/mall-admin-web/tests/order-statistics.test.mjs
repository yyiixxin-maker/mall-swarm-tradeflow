import test from 'node:test'
import assert from 'node:assert/strict'
import { groupOrderSample } from '../src/utils/order-statistics.ts'

test('chart uses real rows and only paid/shipped/completed order amounts', () => {
  assert.deepEqual(groupOrderSample([
    { createTime: '2026-11-17T10:00:00', status: 0, payAmount: 10 },
    { createTime: '2026-11-17T11:00:00', status: 1, payAmount: 0.1 },
    { createTime: '2026-11-17T12:00:00', status: 3, payAmount: 0.2 },
    { createTime: '2026-11-16', status: 4, payAmount: 99 },
    { createTime: 'invalid-date', status: 1, payAmount: 99 },
  ]), [
    { date: '2026-11-16', orderCount: 1, paidAmount: 0 },
    { date: '2026-11-17', orderCount: 3, paidAmount: 0.3 },
  ])
})

test('date filtering is inclusive and empty samples remain empty', () => {
  assert.deepEqual(groupOrderSample([{ createTime: '2026-11-17', status: 2, payAmount: 5 }], ['2026-11-17', '2026-11-17']), [
    { date: '2026-11-17', orderCount: 1, paidAmount: 5 },
  ])
  assert.deepEqual(groupOrderSample([], null), [])
  assert.deepEqual(groupOrderSample([{ createTime: '2026-11-16' }], ['2026-11-17', '2026-11-18']), [])
})
