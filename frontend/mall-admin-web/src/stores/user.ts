import { defineStore } from 'pinia'
import { getAdminInfoAPI, adminLoginAPI, adminLogoutAPI } from '@/apis/admin'
import { ref } from 'vue'
import type { LoginParam, UserInfo } from '@/types/admin'
import { authorizationToken } from '@/utils/api-contract'

export const useUserStore = defineStore(
  'user',
  () => {
    // 用户信息
    const userInfo = ref<UserInfo>({
      username: '',
      avatar: '',
      roles: [],
      token: '',
      menus: [],
    })

    // 用户登录
    const userLogin = async (loginParam: LoginParam) => {
      const res = await adminLoginAPI(loginParam)
      const tokenStr = authorizationToken(res.data.tokenHead, res.data.token)
      userInfo.value.token = tokenStr
      userInfo.value.username = loginParam.username
      try {
        await getUserInfo()
      } catch (error) {
        fedLogout()
        throw error
      }
    }

    // 获取用户信息
    const getUserInfo = async () => {
      const res = await getAdminInfoAPI()
      if (res.data.roles && res.data.roles.length > 0) {
        // 验证返回的roles是否是一个非空数组
        userInfo.value.roles = res.data.roles
      } else {
        throw new Error('该用户暂未分配角色，请先分配角色！')
      }
      userInfo.value.menus = res.data.menus
      userInfo.value.avatar = res.data.icon
    }

    // 用户登出
    const userLogout = async () => {
      await adminLogoutAPI()
      userInfo.value.token = ''
      userInfo.value.roles = []
    }

    // 前端登出
    const fedLogout = () => {
      userInfo.value.token = ''
      userInfo.value.roles = []
      userInfo.value.menus = []
    }

    return {
      userInfo,
      userLogin,
      getUserInfo,
      userLogout,
      fedLogout,
    }
  },
  {
    // 持久化配置
    persist: {
      pick: ['userInfo.username', 'userInfo.avatar', 'userInfo.roles', 'userInfo.token', 'userInfo.menus'],
    },
  },
)
