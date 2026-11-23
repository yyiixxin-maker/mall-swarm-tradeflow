<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getProductListAPI } from '@/apis/product'
import { getOrderListAPI } from '@/apis/order'
import { getAdminListAPI } from '@/apis/admin'
import { groupOrderSample } from '@/utils/order-statistics'
import type { OmsOrder } from '@/types/order'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, LineChart, GridComponent, TooltipComponent, LegendComponent])
const loading = ref(false)
const error = ref('')
const counts = ref<Record<string, number>>({})
const orders = ref<OmsOrder[]>([])
const range = ref<[string, string] | null>(null)
const refreshedAt = ref('')
const cards = computed(() => [
  { title: '商品总数', value: counts.value.products, route: '/pms/product' },
  { title: '已上架商品', value: counts.value.published, route: '/pms/product' },
  { title: '已下架商品', value: counts.value.unpublished, route: '/pms/product' },
  { title: '订单总数', value: counts.value.orders, route: '/oms/order' },
  { title: '待付款订单', value: counts.value.pending, route: '/oms/order' },
  { title: '待发货订单', value: counts.value.awaitingDelivery, route: '/oms/order' },
  { title: '已发货订单', value: counts.value.shipped, route: '/oms/order' },
  { title: '后台账号', value: counts.value.admins, route: '/ums/admin' },
])
const rows = computed(() => groupOrderSample(orders.value, range.value))
const chartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { data: ['订单数量', '已付款订单应付金额'] },
  grid: { left: 50, right: 65, top: 55, bottom: 45 },
  xAxis: { type: 'category', data: rows.value.map(row => row.date), axisLabel: { hideOverlap: true } },
  yAxis: [{ type: 'value', name: '订单数', minInterval: 1 }, { type: 'value', name: '元' }],
  series: [
    { name: '订单数量', type: 'line', data: rows.value.map(row => row.orderCount), itemStyle: { color: '#409eff' } },
    { name: '已付款订单应付金额', type: 'line', yAxisIndex: 1, data: rows.value.map(row => row.paidAmount), itemStyle: { color: '#67c23a' } },
  ],
}))

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [products, published, unpublished, allOrders, pending, awaitingDelivery, shipped, admins] = await Promise.all([
      getProductListAPI({ pageNum: 1, pageSize: 1 }),
      getProductListAPI({ pageNum: 1, pageSize: 1, publishStatus: 1 }),
      getProductListAPI({ pageNum: 1, pageSize: 1, publishStatus: 0 }),
      getOrderListAPI({ pageNum: 1, pageSize: 200 }),
      getOrderListAPI({ pageNum: 1, pageSize: 1, status: 0 }),
      getOrderListAPI({ pageNum: 1, pageSize: 1, status: 1 }),
      getOrderListAPI({ pageNum: 1, pageSize: 1, status: 2 }),
      getAdminListAPI({ pageNum: 1, pageSize: 1 }),
    ])
    counts.value = {
      products: products.data.total, published: published.data.total, unpublished: unpublished.data.total,
      orders: allOrders.data.total, pending: pending.data.total, awaitingDelivery: awaitingDelivery.data.total,
      shipped: shipped.data.total, admins: admins.data.total,
    }
    orders.value = allOrders.data.list
    refreshedAt.value = new Date().toLocaleString()
  } catch {
    error.value = '概览加载失败，请确认后端服务及当前账号权限，然后重试。'
    counts.value = {}
    orders.value = []
  } finally {
    loading.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div class="app-container dashboard">
    <header class="dashboard-header">
      <div>
        <h1>经营概览</h1>
        <p class="muted">来自当前后台真实列表接口；无演示数字。</p>
      </div>
      <el-button type="primary" :loading="loading" @click="refresh">刷新数据</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-loading="loading" class="metric-grid">
      <router-link v-for="card in cards" :key="card.title" :to="card.route" class="metric-card">
        <div class="muted">{{ card.title }}</div>
        <div class="metric-value">{{ card.value ?? '—' }}</div>
        <span class="metric-link">查看列表 →</span>
      </router-link>
    </div>
    <el-card class="statistics-layout">
      <template #header>
        <div class="chart-header">
          <div>
            <strong>订单趋势</strong>
            <p class="muted">已载入 {{ orders.length }} / {{ counts.orders ?? '—' }} 条订单，按接口创建日期聚合。</p>
          </div>
          <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD"
            range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
        </div>
      </template>
      <el-alert title="统计口径" type="info" :closable="false"
        description="图表采用订单列表第 1 页、最多 200 条数据。金额仅汇总待发货、已发货、已完成订单的应付金额，不代表支付渠道对账或完整财务报表；会员增长、退款率等暂无统计接口，不展示估算数据。" />
      <div class="chart" v-loading="loading">
        <v-chart v-if="rows.length" :option="chartOption" autoresize />
        <el-empty v-else :description="error ? '数据加载失败' : '所选日期没有订单数据'" />
      </div>
      <p v-if="refreshedAt && !error" class="muted">最近更新：{{ refreshedAt }}</p>
    </el-card>
  </div>
</template>

<style scoped>
.dashboard { max-width: 1440px; margin: 0 auto; }
.dashboard-header, .chart-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
h1 { margin: 0; font-size: 24px; color: #303133; }
.muted { color: #909399; font-size: 13px; line-height: 1.6; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; margin: 20px 0; }
.metric-card { border: 1px solid #ebeef5; border-radius: 6px; background: #fff; padding: 20px; transition: border-color .2s; }
.metric-card:hover { border-color: #409eff; }
.metric-value { font-size: 30px; line-height: 1.8; color: #303133; }
.metric-link { color: #409eff; font-size: 12px; }
.chart { height: 360px; margin-top: 20px; }
@media (max-width: 1100px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .metric-grid { grid-template-columns: 1fr; } .dashboard { padding: 14px; } }
</style>
