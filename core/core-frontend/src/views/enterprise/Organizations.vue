<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  managementCall,
  managementOptions,
  managementPage,
  type ManagementRecord,
  type ManagementCommand
} from '@/api/enterprise/management'
import { useManagementPage } from './useManagementPage'
const { t, busy, notice, run } = useManagementPage()
const rows = ref<ManagementRecord[]>([])
const options = ref<ManagementRecord[]>([])
const page = ref(1)
const total = ref(0)
const open = ref(false)
const form = reactive({
  id: '',
  version: '',
  kind: 'SCHOOL',
  name: '',
  schoolCode: '',
  parentId: '',
  schoolId: '',
  status: 'ACTIVE'
})
const schools = computed(() =>
  options.value.filter(row => row.kind === 'SCHOOL' && row.status === 'ACTIVE')
)
const parents = computed(() =>
  options.value.filter(row => row.id !== form.id && row.status === 'ACTIVE')
)
const load = async () => {
  rows.value = []
  options.value = []
  const result = await managementPage('organizations/page', page.value)
  rows.value = result.records
  total.value = result.total
  options.value = await managementOptions('organizations/page')
}
onMounted(() => run(load))
const begin = (row?: ManagementRecord) => {
  Object.assign(form, {
    id: row?.id || '',
    version: row?.version || '',
    kind: row?.kind || 'SCHOOL',
    name: row?.name || '',
    schoolCode: row?.schoolCode || '',
    parentId: row?.parentId || '',
    schoolId: row?.schoolId || '',
    status: row?.status || 'ACTIVE'
  })
  notice.value = ''
  open.value = true
}
const save = () =>
  run(async () => {
    const command: ManagementCommand = {
      mode: form.id ? 'UPDATE' : 'CREATE',
      kind: form.kind,
      name: form.name,
      parentId: form.parentId || null,
      status: form.status
    }
    if (form.id) {
      command.id = form.id
      command.expectedVersion = form.version
    }
    if (form.kind === 'SCHOOL') command.schoolCode = form.schoolCode
    else command.schoolId = form.schoolId || null
    await managementCall('organizations/save', command, true)
    open.value = false
    await load()
  })
</script>
<template>
  <section>
    <h2>{{ t('enterprise.organizations') }}</h2>
    <el-alert
      v-if="notice && !open"
      :title="notice"
      type="warning"
      :closable="false"
      role="alert"
    />
    <el-button type="primary" :disabled="busy" @click="begin()">{{
      t('enterprise.createOrganization')
    }}</el-button>
    <el-button :loading="busy" @click="run(load)">{{ t('enterprise.refresh') }}</el-button>
    <el-table :data="rows" row-key="id">
      <el-table-column prop="name" :label="t('enterprise.name')" /><el-table-column
        prop="id"
        label="ID"
      />
      <el-table-column :label="t('enterprise.kind')"
        ><template #default="{ row }">{{
          t(`enterprise.${row.kind === 'SCHOOL' ? 'school' : 'department'}`)
        }}</template></el-table-column
      >
      <el-table-column prop="schoolCode" :label="t('enterprise.schoolCode')" /><el-table-column
        prop="parentId"
        :label="t('enterprise.parent')"
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
      :title="t('enterprise.organization')"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      width="560px"
    >
      <el-alert v-if="notice" :title="notice" type="warning" :closable="false" role="alert" />
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item :label="t('enterprise.kind')"
          ><el-select v-model="form.kind" :disabled="!!form.id"
            ><el-option value="SCHOOL" :label="t('enterprise.school')" /><el-option
              value="DEPARTMENT"
              :label="t('enterprise.department')" /></el-select
        ></el-form-item>
        <el-form-item :label="t('enterprise.name')"
          ><el-input v-model="form.name" name="organizationName" maxlength="128"
        /></el-form-item>
        <el-form-item v-if="form.kind === 'SCHOOL'" :label="t('enterprise.schoolCode')"
          ><el-input
            v-model="form.schoolCode"
            name="schoolCode"
            maxlength="64"
            :disabled="!!form.id"
        /></el-form-item>
        <el-form-item v-else :label="t('enterprise.school')"
          ><el-select v-model="form.schoolId" clearable :disabled="!!form.id"
            ><el-option
              v-for="school in schools"
              :key="school.id"
              :value="school.id"
              :label="school.name" /></el-select
        ></el-form-item>
        <el-form-item :label="t('enterprise.parent')"
          ><el-select v-model="form.parentId" clearable
            ><el-option
              v-for="parent in parents"
              :key="parent.id"
              :value="parent.id"
              :label="parent.name" /></el-select
        ></el-form-item>
        <el-form-item :label="t('enterprise.status')"
          ><el-select v-model="form.status"
            ><el-option value="ACTIVE" :label="t('enterprise.ACTIVE')" /><el-option
              value="DISABLED"
              :label="t('enterprise.DISABLED')" /></el-select
        ></el-form-item>
        <el-button
          native-type="submit"
          type="primary"
          :loading="busy"
          :disabled="!form.name || (form.kind === 'SCHOOL' && !form.schoolCode)"
          >{{ t('enterprise.save') }}</el-button
        >
        <el-button :disabled="busy" @click="open = false">{{ t('enterprise.cancel') }}</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
