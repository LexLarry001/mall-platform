import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/tokens.css'
import './styles/app.css'

// bfcache（浏览器后退）恢复页面时强制重载，确保路由守卫重新校验门户与登录态
window.addEventListener('pageshow', (event) => {
  if (event.persisted) window.location.reload()
})

createApp(App).use(createPinia()).use(router).mount('#app')
