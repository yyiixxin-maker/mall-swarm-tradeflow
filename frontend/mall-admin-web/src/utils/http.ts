import { useUserStore } from '@/stores/user'
import { businessFailure } from '@/utils/api-contract'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({
  baseURL: import.meta.env.VITE_BASE_SERVER_URL,
  timeout: 15000,
})

let redirecting = false
function expireSession() {
  useUserStore().fedLogout()
  if (!redirecting && !location.hash.startsWith('#/login')) {
    redirecting = true
    ElMessage.warning('登录已过期，请重新登录')
    location.hash = '/login'
    location.reload()
  }
}

http.interceptors.request.use(config => {
  const token = useUserStore().userInfo.token
  if (token) config.headers.Authorization = token
  return config
})

http.interceptors.response.use(
  response => {
    const failure = businessFailure(response.data)
    if (failure) {
      if (response.data?.code === 401) expireSession()
      else ElMessage.error(failure)
      return Promise.reject(new Error(failure))
    }
    return response.data
  },
  error => {
    if (error.response?.status === 401) expireSession()
    else {
      const message = error.response?.status === 403
        ? '没有此操作权限，请联系管理员'
        : error.code === 'ECONNABORTED'
          ? '请求超时，请检查后端状态后重试'
          : '无法完成请求，请检查网络和后端服务'
      ElMessage.error(message)
    }
    // Axios errors carry request configuration, including passwords; never log the raw object.
    return Promise.reject(new Error('接口请求失败'))
  },
)

export default http
