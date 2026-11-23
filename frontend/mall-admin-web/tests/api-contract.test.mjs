import test from 'node:test'
import assert from 'node:assert/strict'
import { authorizationToken, businessFailure } from '../src/utils/api-contract.ts'

test('HTTP success is not business success', () => {
  assert.equal(businessFailure({ code: 200, data: [] }), undefined)
  assert.equal(businessFailure({ code: 500, message: '库存不足' }), '库存不足')
  assert.equal(businessFailure({ code: 401 }), '请求未成功，请稍后重试')
  assert.equal(businessFailure('<html>proxy failed</html>'), '服务器未返回有效的业务结果')
})

test('Bearer token has exactly one separating space and invalid payloads fail closed', () => {
  assert.equal(authorizationToken('Bearer ', ' token '), 'Bearer token')
  assert.throws(() => authorizationToken('', 'token'))
  assert.throws(() => authorizationToken('Bearer', ''))
})
