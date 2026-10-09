<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import router from '@/router'
import {
  managementCall,
  managementPage,
  switchGroup,
  type ManagementRecord
} from '@/api/enterprise/management'
import { useManagementPage } from './useManagementPage'
const { t, store, busy, notice, run } = useManagementPage()
const rows = ref<ManagementRecord[]>([])
const page = ref(1)
const total = ref(0)
const user = reactive({ username: '', displayName: '', temporaryPassword: '' })
const group = reactive({ code: '', name: '', administratorUserId: '' })
const createdUser = ref('')
const load = async () => {
  rows.value = []
  const result = await managementPage('context/tenants', page.value, false)
  rows.value = result.records
  total.value = result.total
}
onMounted(() => run(load))
const enter = (id: string) =>
  run(async () => {
    await switchGroup(id)
    await router.push(store.can('MANAGE_ORGANIZATIONS') ? '/organizations' : '/groups')
  })
const createUser = () =>
  run(async () => {
    try {
      const result = await managementCall<{ id: string }>('users/create', { ...user })
      createdUser.value = result.id
      group.administratorUserId = result.id
      user.username = ''
      user.displayName = ''
    } finally {
      user.temporaryPassword = ''
    }
  })
const createGroup = () =>
  run(async () => {
    await managementCall('tenants/create', { ...group })
    group.code = ''
    group.name = ''
    group.administratorUserId = ''
    await load()
  })
</script>
<template>
  <section>
    <h2>{{ t('enterprise.groups') }}</h2>
    <el-alert v-if="notice" :title="notice" type="warning" :closable="false" role="alert" />
    <el-button :loading="busy" @click="run(load)">{{ t('enterprise.refresh') }}</el-button>
    <el-table :data="rows" row-key="id">
      <el-table-column prop="name" :label="t('enterprise.name')" /><el-table-column
        prop="code"
        :label="t('enterprise.code')"
      />
      <el-table-column prop="id" label="ID" />
      <el-table-column :label="t('enterprise.actions')"
        ><template #default="{ row }"
          ><el-button :disabled="busy" @click="enter(row.id)">{{
            t('enterprise.enterGroup')
          }}</el-button></template
        ></el-table-column
      >
    </el-table>
    <el-pagination
      :disabled="busy"
      v-model:current-page="page"
      :page-size="20"
      :total="total"
      layout="prev, pager, next, total"
      @current-change="run(load)"
    />
    <template v-if="store.platform">
      <h3>{{ t('enterprise.createUser') }}</h3>
      <el-form label-position="top" class="management-form" @submit.prevent="createUser">
        <el-form-item :label="t('enterprise.username')"
          ><el-input v-model="user.username" name="newUsername" maxlength="64"
        /></el-form-item>
        <el-form-item :label="t('enterprise.displayName')"
          ><el-input v-model="user.displayName" name="displayName" maxlength="128"
        /></el-form-item>
        <el-form-item :label="t('enterprise.temporaryPassword')"
          ><el-input
            v-model="user.temporaryPassword"
            name="temporaryPassword"
            type="password"
            autocomplete="new-password"
        /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('enterprise.createUser')
        }}</el-button>
        <p v-if="createdUser" data-testid="created-user">
          {{ t('enterprise.userId') }}: {{ createdUser }}
        </p>
      </el-form>
      <h3>{{ t('enterprise.createGroup') }}</h3>
      <el-form label-position="top" class="management-form" @submit.prevent="createGroup">
        <el-form-item :label="t('enterprise.code')"
          ><el-input v-model="group.code" name="groupCode" maxlength="64"
        /></el-form-item>
        <el-form-item :label="t('enterprise.name')"
          ><el-input v-model="group.name" name="groupName" maxlength="128"
        /></el-form-item>
        <el-form-item :label="t('enterprise.administratorUserId')"
          ><el-input v-model="group.administratorUserId" name="administratorUserId"
        /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('enterprise.createGroup')
        }}</el-button>
      </el-form>
    </template>
  </section>
</template>
<style scoped>
.management-form {
  max-width: 560px;
}
</style>
