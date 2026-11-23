import type { MemberInfo } from '@/types/member'
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loginAPI, getMemberInfoAPI } from '@/apis/member'

// 定义会员Store
export const useMemberStore = defineStore(
  'member',
  () => {
    // 会员信息
    const memberInfo = ref<MemberInfo>()

    // 是否已登录
    const hasLogin = computed(() => !!memberInfo.value)

    // 保存会员信息，登录时使用
    const setMemberInfo = (val: MemberInfo) => {
      const { id, username, nickname, icon, integration, growth } = val
      memberInfo.value = { id, username, nickname, icon, integration, growth }
    }

    // 会员登录（核心业务逻辑，不包含 UI 交互）
    const memberLogin = async (username: string, password: string) => {
      // 1. 调用登录接口获取 token
      const loginRes = await loginAPI({ username, password })

      // 2. 拼接完整 token 并存储
      const loginData = loginRes.data
      if (!loginData?.token || !loginData.tokenHead) throw new Error('登录响应缺少凭证')
      const token = `${loginData.tokenHead.trim()} ${loginData.token}`
      uni.setStorageSync('token', token)
      uni.setStorageSync('username', username)
      uni.removeStorageSync('password')

      // 3. 获取用户信息
      try {
        const memberRes = await getMemberInfoAPI()
        setMemberInfo(memberRes.data)
      } catch (error) {
        memberLogout()
        throw error
      }
    }

    // 会员退出登录
    const memberLogout = () => {
      memberInfo.value = undefined
      uni.removeStorageSync('token')
      uni.removeStorageSync('password')
      uni.removeStorageSync('pendingCheckout')
    }

    return {
      memberInfo,
      hasLogin,
      setMemberInfo,
      memberLogin,
      memberLogout,
    }
  },
  // TODO: 持久化
  {
    // 网页端用法
    // persist: true,
    // 兼容多端的用法
    persist: {
      afterRestore: ({ store }) => {
        if (store.memberInfo) store.setMemberInfo(store.memberInfo)
      },
      storage: {
        setItem(key, value) {
          uni.setStorageSync(key, value)
        },
        getItem(key) {
          return uni.getStorageSync(key)
        },
      },
    },
  },
)
