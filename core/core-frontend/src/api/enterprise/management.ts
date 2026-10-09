import request from '@/config/axios'
import { EnterpriseApiError } from '@/config/axios/enterpriseContext'
import {
  useEnterpriseStoreWithOut,
  type ManagementNavigation,
  type ManagementPrincipal
} from '@/store/modules/enterprise'

export interface ManagementRecord {
  id: string
  version: string
  name?: string
  code?: string
  status: 'ACTIVE' | 'DISABLED'
  kind?: 'SCHOOL' | 'DEPARTMENT'
  schoolCode?: string | null
  parentId?: string | null
  schoolId?: string | null
  userId?: string
  displayName?: string
  organizationIds?: string[]
  memberId?: string
  roleId?: string
  schoolIds?: string[]
}
export interface ManagementPage<T> {
  records: T[]
  total: number
}
interface Envelope<T> {
  code: number
  data: T
}
export type ManagementCommand = Record<string, string | string[] | null>

export const managementCall = async <T>(
  path: string,
  data: object = {},
  group = false,
  anonymous = false
): Promise<T> => {
  const store = useEnterpriseStoreWithOut()
  const generation = store.generation
  const controller = store.begin()
  const headers: Record<string, string> = {}
  if (!anonymous) headers.Authorization = `Bearer ${store.credential}`
  if (group) {
    if (!store.principal?.tenantId) {
      store.finish(controller)
      throw new EnterpriseApiError(70001)
    }
    headers['X-DE-Context-Tenant'] = store.principal.tenantId
    headers['X-DE-Context-Version'] = store.principal.version
  }
  try {
    const result = await request.post<Promise<Envelope<T>>>({
      url: `/api/enterprise/v1/${path}`,
      data,
      headers,
      signal: controller.signal
    })
    if (store.generation !== generation) throw new DOMException('Context changed', 'AbortError')
    return result.data
  } catch (error) {
    if (
      store.generation === generation &&
      error instanceof EnterpriseApiError &&
      (error.status === 401 || error.code === 20001)
    )
      store.clear()
    throw error
  } finally {
    store.finish(controller)
  }
}
export const refreshNavigation = async () => {
  const store = useEnterpriseStoreWithOut()
  const navigation = await managementCall<ManagementNavigation>('context/navigation')
  store.principal = navigation
  store.navigation = navigation
  return navigation
}
export const switchGroup = async (tenantId: string) => {
  const store = useEnterpriseStoreWithOut()
  if (!store.principal) throw new EnterpriseApiError(20001)
  const expectedVersion = store.principal.version
  store.transitioning = true
  store.invalidate()
  try {
    store.principal = await managementCall<ManagementPrincipal>('context/switch', {
      tenantId,
      expectedVersion
    })
    await refreshNavigation()
  } catch (error) {
    store.clear()
    throw error
  } finally {
    store.transitioning = false
  }
}
export const managementPage = (path: string, pageNum: number, group = true) =>
  managementCall<ManagementPage<ManagementRecord>>(path, { pageNum, pageSize: 20 }, group)
export const managementOptions = async (path: string) => {
  const rows: ManagementRecord[] = []
  for (let pageNum = 1; ; pageNum++) {
    const page = await managementCall<ManagementPage<ManagementRecord>>(
      path,
      { pageNum, pageSize: 100 },
      true
    )
    rows.push(...page.records)
    if (rows.length >= page.total) return rows
    if (rows.length >= 5000 || page.records.length === 0) throw new EnterpriseApiError(60003)
  }
}
