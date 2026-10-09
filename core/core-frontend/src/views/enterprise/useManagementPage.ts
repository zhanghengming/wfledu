import { ref } from 'vue'
import { useI18n } from '@/hooks/web/useI18n'
import { EnterpriseApiError } from '@/config/axios/enterpriseContext'
import { useEnterpriseStore } from '@/store/modules/enterprise'

export const useManagementPage = () => {
  const { t } = useI18n()
  const store = useEnterpriseStore()
  const busy = ref(false)
  const notice = ref('')
  const run = async (action: () => Promise<void>) => {
    if (busy.value) return
    const generation = store.generation
    notice.value = ''
    busy.value = true
    try {
      await action()
    } catch (error) {
      if (store.generation !== generation && store.credential) return
      const code = error instanceof EnterpriseApiError ? error.code : 60003
      notice.value = t(
        `enterprise.errors.${
          [10001, 20001, 50002, 50003, 70001, 70002].includes(code) ? code : 60003
        }`
      )
    } finally {
      busy.value = false
    }
  }
  return { t, store, busy, notice, run }
}
