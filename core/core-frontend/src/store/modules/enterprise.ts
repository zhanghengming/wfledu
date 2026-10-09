import { defineStore } from 'pinia'
import { store } from '@/store'

export interface ManagementPrincipal {
  sessionId: string
  version: string
  userId: string
  tenantId: string | null
  mustReset: boolean
}
export interface ManagementNavigation extends ManagementPrincipal {
  accessEpoch: string | null
  platformCapabilities: string[]
  groupCapabilities: string[]
}
const key = 'de.enterprise.management.credential.v1'
const controllers = new Set<AbortController>()
const storedCredential = () => {
  try {
    return sessionStorage.getItem(key) || ''
  } catch {
    return ''
  }
}
const persist = (credential: string) => {
  try {
    if (credential) sessionStorage.setItem(key, credential)
    else sessionStorage.removeItem(key)
  } catch {
    // A restricted browser can keep the credential in memory for this tab.
  }
}
export const useEnterpriseStore = defineStore('enterpriseManagement', {
  state: () => ({
    credential: storedCredential(),
    principal: null as ManagementPrincipal | null,
    navigation: null as ManagementNavigation | null,
    generation: 0,
    transitioning: false
  }),
  getters: {
    can: state => (capability: string) =>
      state.navigation?.groupCapabilities.includes(capability) === true,
    platform: state => state.navigation?.platformCapabilities.includes('PLATFORM_OPERATE') === true
  },
  actions: {
    invalidate() {
      this.generation++
      controllers.forEach(controller => controller.abort())
      controllers.clear()
      this.navigation = null
    },
    establish(credential: string, principal: ManagementPrincipal) {
      this.clear()
      this.credential = credential
      this.principal = principal
      persist(credential)
    },
    clear() {
      this.invalidate()
      this.credential = ''
      this.principal = null
      this.transitioning = false
      persist('')
    },
    begin() {
      const controller = new AbortController()
      controllers.add(controller)
      return controller
    },
    finish(controller: AbortController) {
      controllers.delete(controller)
    }
  }
})
export const useEnterpriseStoreWithOut = () => useEnterpriseStore(store)
