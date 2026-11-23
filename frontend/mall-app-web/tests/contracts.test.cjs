const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const storage = new Map()
let pendingResponse
let redirected = 0
let loggedOut = 0
let sent
let interceptor
const uni = {
  getStorageSync: (key) => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  addInterceptor: (_, value) => { interceptor = value },
  showToast: () => {},
  navigateTo: (options) => { redirected++; options.complete?.() },
  request: (options) => {
    interceptor.invoke(options)
    sent = options
    if (pendingResponse.networkError) options.fail(pendingResponse)
    else options.success(pendingResponse)
  },
}
const load = (file) => {
  const source = fs.readFileSync(path.join(__dirname, '..', file), 'utf8')
    .replace('import.meta.env.VITE_API_BASE_URL', "'/api/mall-portal'")
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} }
  vm.runInNewContext(code, {
    module, exports: module.exports, uni, crypto: globalThis.crypto, getCurrentPages: () => [],
    require: () => ({ useMemberStore: () => ({ memberLogout: () => { loggedOut++; storage.delete('token') } }) }),
  })
  return module.exports
}

async function main() {
  const { calculateCheckoutAmount } = load('src/utils/checkout.ts')
  const amount = { totalAmount: 100, promotionAmount: 10, freightAmount: 5 }
  assert.equal(calculateCheckoutAmount(amount, 5, 100, { useUnit: 100, couponStatus: 1 }), 89)
  assert.equal(calculateCheckoutAmount(amount, 5, 100, { useUnit: 100, couponStatus: 0 }), 90)
  assert.equal(calculateCheckoutAmount(amount, 0, 100, { useUnit: 100, couponStatus: 0 }), 94)
  assert.equal(calculateCheckoutAmount(amount, 0, 100, { useUnit: 0, couponStatus: 0 }), 95)
  const checkout = { cartIds: [3, 1], memberReceiveAddressId: 1, payType: 0, useIntegration: 0 }
  const first = load('src/utils/checkout.ts').getCheckoutRequestKey(checkout)
  assert.match(first, /^[A-Za-z0-9_-]{8,64}$/)
  assert.equal(load('src/utils/checkout.ts').getCheckoutRequestKey({ ...checkout, cartIds: [1, 3] }), first, 'reload/retry must reuse the persisted key')
  assert.notEqual(load('src/utils/checkout.ts').getCheckoutRequestKey({ ...checkout, memberReceiveAddressId: 2 }), first, 'different checkout must get a fresh key')
  const { http } = load('src/utils/http.ts')
  storage.set('token', 'Bearer fixture-token')
  pendingResponse = { statusCode: 200, data: { code: 200, data: [] } }
  await http({ url: '/product/search?sort=1', params: { keyword: '手机', missing: undefined } })
  assert.equal(sent.url, '/api/mall-portal/product/search?sort=1&keyword=%E6%89%8B%E6%9C%BA')
  assert.equal(sent.header.Authorization, 'Bearer fixture-token')
  pendingResponse = { statusCode: 200, data: { code: 500, message: 'fixture business failure' } }
  await assert.rejects(http({ url: '/order/generateOrder', method: 'POST' }), /fixture business failure/)
  pendingResponse = { statusCode: 200, data: null }
  await assert.rejects(http({ url: '/home/content' }), /响应格式异常/)
  pendingResponse = { statusCode: 401, data: { message: 'expired' } }
  await assert.rejects(http({ url: '/sso/info' }))
  assert.equal(loggedOut, 1)
  assert.equal(redirected, 1)
  pendingResponse = { statusCode: 200, data: { code: 401 } }
  await assert.rejects(http({ url: '/sso/info' }))
  assert.equal(loggedOut, 2)
  pendingResponse = { statusCode: 403, data: { message: 'forbidden' } }
  await assert.rejects(http({ url: '/sso/info' }))
  assert.equal(loggedOut, 2, 'permission failure must not erase a valid session')
  pendingResponse = { statusCode: 502, data: null }
  await assert.rejects(http({ url: '/home/content' }), /502/)
  pendingResponse = { networkError: true }
  await assert.rejects(http({ url: '/home/content' }), /连接服务失败/)
  console.log('PASS: persisted checkout keys, gateway URL/query encoding, Bearer token, business failures, expiration, permissions and network failures')
}
main().catch((error) => { console.error(error.message); process.exitCode = 1 })
