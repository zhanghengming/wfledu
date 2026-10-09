<script setup lang="ts">
import { reactive } from 'vue'
import router from '@/router'
import { managementCall, refreshNavigation } from '@/api/enterprise/management'
import type { ManagementPrincipal } from '@/store/modules/enterprise'
import { useManagementPage } from './useManagementPage'

const { t, store, busy, notice, run } = useManagementPage()
const form = reactive({
  username: '',
  password: '',
  previousPassword: '',
  newPassword: '',
  confirmPassword: ''
})
const login = () =>
  run(async () => {
    try {
      const result = await managementCall<ManagementPrincipal & { credential: string }>(
        'auth/login',
        {
          username: form.username,
          password: form.password
        },
        false,
        true
      )
      const { credential, ...principal } = result
      store.establish(credential, principal)
      if (!principal.mustReset) {
        await refreshNavigation()
        await router.replace('/groups')
      }
    } finally {
      form.password = ''
    }
  })
const changePassword = () =>
  run(async () => {
    if (form.newPassword !== form.confirmPassword) {
      notice.value = t('enterprise.passwordMismatch')
      return
    }
    try {
      await managementCall('auth/password', {
        previousPassword: form.previousPassword,
        newPassword: form.newPassword
      })
      store.clear()
      notice.value = t('enterprise.passwordChanged')
    } finally {
      form.previousPassword = ''
      form.newPassword = ''
      form.confirmPassword = ''
    }
  })
</script>
<template>
  <main class="management-login">
    <h1>{{ t('enterprise.title') }}</h1>
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" role="alert" />
    <el-form v-if="!store.principal?.mustReset" label-position="top" @submit.prevent="login">
      <el-form-item :label="t('enterprise.username')"
        ><el-input v-model="form.username" name="username" autocomplete="username" maxlength="64"
      /></el-form-item>
      <el-form-item :label="t('enterprise.password')"
        ><el-input
          v-model="form.password"
          name="password"
          type="password"
          autocomplete="current-password"
          show-password
      /></el-form-item>
      <el-button
        native-type="submit"
        type="primary"
        :loading="busy"
        :disabled="!form.username || !form.password"
        >{{ t('enterprise.login') }}</el-button
      >
    </el-form>
    <el-form v-else label-position="top" @submit.prevent="changePassword">
      <p>{{ t('enterprise.mustReset') }}</p>
      <el-form-item :label="t('enterprise.previousPassword')"
        ><el-input
          v-model="form.previousPassword"
          name="previousPassword"
          type="password"
          autocomplete="current-password"
      /></el-form-item>
      <el-form-item :label="t('enterprise.newPassword')"
        ><el-input
          v-model="form.newPassword"
          name="newPassword"
          type="password"
          autocomplete="new-password"
      /></el-form-item>
      <el-form-item :label="t('enterprise.confirmPassword')"
        ><el-input
          v-model="form.confirmPassword"
          name="confirmPassword"
          type="password"
          autocomplete="new-password"
      /></el-form-item>
      <el-button
        native-type="submit"
        type="primary"
        :loading="busy"
        :disabled="!form.previousPassword || !form.newPassword"
        >{{ t('enterprise.changePassword') }}</el-button
      >
      <el-button :disabled="busy" @click="store.clear()">{{ t('enterprise.cancel') }}</el-button>
    </el-form>
  </main>
</template>
<style scoped>
.management-login {
  max-width: 440px;
  margin: 8vh auto;
  padding: 32px;
}
</style>
