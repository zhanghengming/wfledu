import router from '@/router'
import { refreshNavigation } from '@/api/enterprise/management'
import { useEnterpriseStoreWithOut } from '@/store/modules/enterprise'

export const setupEnterpriseGuard = () => {
  router.beforeEach(async to => {
    const store = useEnterpriseStoreWithOut()
    if (to.path === '/login') return true
    if (!store.credential || store.principal?.mustReset) return '/login'
    try {
      await refreshNavigation()
    } catch {
      store.clear()
      return '/login'
    }
    const capability = to.meta.capability
    if (typeof capability === 'string' && !store.can(capability)) return '/groups'
    return true
  })
}
