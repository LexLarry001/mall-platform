import axios from 'axios'
import type { AxiosInstance } from 'axios'
import { clearSession, readToken } from '../utils/session'

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

http.interceptors.request.use((config) => {
  const token = readToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown>
    if (body && typeof body.code === 'number' && body.code !== 0) {
      if (body.code === 401) {
        redirectToLogin()
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return response
  },
  (error) => {
    if (error?.response?.status === 401) {
      redirectToLogin()
    }
    return Promise.reject(error)
  },
)

function redirectToLogin(): void {
  clearSession()
  if (!window.location.pathname.startsWith('/login')) {
    window.location.href = '/login'
  }
}

export default http
