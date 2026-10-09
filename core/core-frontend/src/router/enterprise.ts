import type { RouteRecordRaw } from 'vue-router_2'

export const enterpriseRoutes: RouteRecordRaw[] = [
  { path: '/', redirect: '/groups' },
  {
    path: '/login',
    name: 'enterpriseLogin',
    component: () => import('@/views/enterprise/Login.vue')
  },
  {
    path: '/',
    component: () => import('@/views/enterprise/Layout.vue'),
    children: [
      { path: 'groups', component: () => import('@/views/enterprise/Groups.vue') },
      {
        path: 'organizations',
        meta: { capability: 'MANAGE_ORGANIZATIONS' },
        component: () => import('@/views/enterprise/Organizations.vue')
      },
      {
        path: 'members',
        meta: { capability: 'MANAGE_MEMBERS' },
        component: () => import('@/views/enterprise/Members.vue')
      },
      {
        path: 'roles',
        meta: { capability: 'MANAGE_ROLES' },
        component: () => import('@/views/enterprise/Roles.vue')
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/groups' }
]
