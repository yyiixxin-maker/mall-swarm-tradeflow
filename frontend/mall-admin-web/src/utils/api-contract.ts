export function authorizationToken(tokenHead: string, token: string) {
  if (!tokenHead?.trim() || !token?.trim()) throw new Error('登录接口没有返回有效令牌')
  return `${tokenHead.trim()} ${token.trim()}`
}

export function businessFailure(payload: unknown): string | undefined {
  if (!payload || typeof payload !== 'object' || !('code' in payload)) {
    return '服务器未返回有效的业务结果'
  }
  const result = payload as { code: unknown; message?: unknown }
  if (result.code === 200) return undefined
  return typeof result.message === 'string' && result.message ? result.message : '请求未成功，请稍后重试'
}
