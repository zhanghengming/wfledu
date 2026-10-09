export const isManagementPage = () => window.location.pathname.endsWith('/enterprise.html')

export const isEnterpriseEndpoint = (url?: string) =>
  typeof url === 'string' && /^\/api\/enterprise\/v1\/[a-z-]+\/[a-z-]+$/.test(url)

export class EnterpriseApiError extends Error {
  constructor(public readonly code: number, public readonly status = 0) {
    super('Enterprise request rejected')
    this.name = 'EnterpriseApiError'
  }
}
