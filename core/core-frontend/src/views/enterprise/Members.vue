<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  managementCall,
  managementOptions,
  managementPage,
  type ManagementCommand,
  type ManagementRecord
} from '@/api/enterprise/management'
import { useManagementPage } from './useManagementPage'
const { t, store, busy, notice, run } = useManagementPage()
const rows = ref<ManagementRecord[]>([])
const options = ref<ManagementRecord[]>([])
const page = ref(1)
const total = ref(0)
const open = ref(false)
const form = reactive({
  id: '',
  version: '',
  userId: '',
  status: 'ACTIVE',
  organizationIds: [] as string[]
})
const load = async () => {
  rows.value = []
  options.value = []
  const result = await managementPage('members/page', page.value)
  rows.value = result.records
  total.value = result.total
  if (store.can('MANAGE_ORGANIZATIONS'))
    options.value = await managementOptions('organizations/page')
}
onMounted(() => run(load))
const begin = (row?: ManagementRecord) => {
  Object.assign(form, {
    id: row?.id || '',
    version: row?.version || '',
    userId: row?.userId || '',
    status: row?.status || 'ACTIVE',
    organizationIds: [...(row?.organizationIds || [])]
  })
  notice.value = ''
  open.value = true
}
const save = () =>
  run(async () => {
    const command: ManagementCommand = {
      mode: form.id ? 'UPDATE' : 'CREATE',
      userId: form.userId,
      status: form.status,
      organizationIds: [...form.organizationIds]
    }
    if (form.id) {
      command.id = form.id
      command.expectedVersion = form.version
    }
    await managementCall('members/save', command, true)
    open.value = false
    await load()
  })
</script>
<template>
  <section>
    <h2>{{ t('enterprise.members') }}</h2>
    <el-alert
      v-if="notice && !open"
      :title="notice"
      type="warning"
      :closable="false"
      role="alert"
    />
    <el-button type="primary" :disabled="busy" @click="begin()">{{
      t('enterprise.addMember')
    }}</el-button>
    <el-button :loading="busy" @click="run(load)">{{ t('enterprise.refresh') }}</el-button>
    <el-table :data="rows" row-key="id">
      <el-table-column prop="id" :label="t('enterprise.memberId')" /><el-table-column
        prop="userId"
        :label="t('enterprise.userId')"
      />
      <el-table-column prop="displayName" :label="t('enterprise.displayName')" /><el-table-column
        prop="organizationIds"
        :label="t('enterprise.organizations')"
      />
      <el-table-column :label="t('enterprise.status')"
        ><template #default="{ row }">{{
          t(`enterprise.${row.status}`)
        }}</template></el-table-column
      >
      <el-table-column :label="t('enterprise.actions')"
        ><template #default="{ row }"
          ><el-button :disabled="busy" @click="begin(row)">{{
            t('enterprise.edit')
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
    <el-dialog
      v-model="open"
      :title="t('enterprise.member')"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      width="560px"
    >
      <el-alert v-if="notice" :title="notice" type="warning" :closable="false" role="alert" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('enterprise.userId')"
          ><el-input v-model="form.userId" name="memberUserId" :disabled="!!form.id"
        /></el-form-item>
        <p>{{ t('enterprise.existingUserHint') }}</p>
        <el-form-item :label="t('enterprise.organizations')"
          ><el-select
            v-model="form.organizationIds"
            multiple
            filterable
            :allow-create="!store.can('MANAGE_ORGANIZATIONS')"
            ><el-option
              v-for="org in options"
              :key="org.id"
              :value="org.id"
              :label="org.name" /></el-select
        ></el-form-item>
        <el-form-item :label="t('enterprise.status')"
          ><el-select v-model="form.status"
            ><el-option value="ACTIVE" :label="t('enterprise.ACTIVE')" /><el-option
              value="DISABLED"
              :label="t('enterprise.DISABLED')" /></el-select
        ></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy" :disabled="!form.userId">{{
          t('enterprise.save')
        }}</el-button>
        <el-button :disabled="busy" @click="open = false">{{ t('enterprise.cancel') }}</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
