import { createApp } from 'vue'
import 'normalize.css/normalize.css'
import '@/style/index.less'
import App from './App.vue'
import { setupStore } from '@/store'
import { setupI18n } from '@/plugins/vue-i18n'
import { setupRouter } from '@/router'
import { setupEnterpriseGuard } from '@/router/enterpriseGuard'
import { setupElementPlus } from '@/plugins/element-plus'

const setup = async () => {
  const app = createApp(App)
  setupStore(app)
  await setupI18n(app)
  setupElementPlus(app)
  setupEnterpriseGuard()
  setupRouter(app)
  app.mount('#app')
}
setup()
