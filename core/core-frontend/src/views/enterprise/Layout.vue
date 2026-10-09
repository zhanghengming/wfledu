<script setup lang="ts">
import { watch } from 'vue'
import router from '@/router'
import { managementCall } from '@/api/enterprise/management'
import { useManagementPage } from './useManagementPage'
const { t, store, busy, notice, run } = useManagementPage()
watch(
  () => store.credential,
  credential => {
    if (!credential) router.replace('/login')
  }
)
const logout = () =>
  run(async () => {
    try {
      await managementCall('auth/logout')
    } finally {
      store.clear()
    }
  })
</script>
<template>
  <el-container class="management-layout">
    <el-header class="management-header">
      <strong>{{ t('enterprise.title') }}</strong>
      <span
        >{{ t('enterprise.currentGroup') }}:
        {{ store.principal?.tenantId || t('enterprise.notSelected') }}</span
      >
      <el-button :loading="busy" @click="logout">{{ t('enterprise.logout') }}</el-button>
    </el-header>
    <el-container>
      <el-aside width="210px">
        <el-menu
          v-if="
            store.credential &&
            store.principal &&
            !store.principal.mustReset &&
            !store.transitioning
          "
          router
          :default-active="$route.path"
        >
          <el-menu-item index="/groups">{{ t('enterprise.groups') }}</el-menu-item>
          <el-menu-item v-if="store.can('MANAGE_ORGANIZATIONS')" index="/organizations">{{
            t('enterprise.organizations')
          }}</el-menu-item>
          <el-menu-item v-if="store.can('MANAGE_MEMBERS')" index="/members">{{
            t('enterprise.members')
          }}</el-menu-item>
          <el-menu-item v-if="store.can('MANAGE_ROLES')" index="/roles">{{
            t('enterprise.roles')
          }}</el-menu-item>
        </el-menu>
      </el-aside>
      <el-main>
        <el-alert v-if="notice" :title="notice" type="warning" :closable="false" />
        <router-view
          v-if="
            store.credential &&
            store.principal &&
            !store.principal.mustReset &&
            !store.transitioning
          "
          :key="`${store.principal?.tenantId}:${store.generation}`"
        />
      </el-main>
    </el-container>
  </el-container>
</template>
<style scoped>
.management-layout {
  min-height: 100vh;
}
.management-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #ddd;
  gap: 20px;
}
</style>
