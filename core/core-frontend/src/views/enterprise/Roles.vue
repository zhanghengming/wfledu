<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  managementCall,
  managementOptions,
  managementPage,
  type ManagementCommand,
  type ManagementRecord
} from '@/api/enterprise/management'
import { useManagementPage } from './useManagementPage'
const { t, store, busy, notice, run } = useManagementPage()
const roles = ref<ManagementRecord[]>([])
const assignments = ref<ManagementRecord[]>([])
const roleOptions = ref<ManagementRecord[]>([])
const members = ref<ManagementRecord[]>([])
const schools = ref<ManagementRecord[]>([])
const rolePage = ref(1)
const assignmentPage = ref(1)
const roleTotal = ref(0)
const assignmentTotal = ref(0)
const open = ref('')
const role = reactive({ id: '', version: '', code: '', name: '', status: 'ACTIVE' })
const assignment = reactive({
  id: '',
  version: '',
  memberId: '',
  roleId: '',
  schoolIds: [] as string[],
  status: 'ACTIVE'
})
const selectableSchools = computed(() =>
  schools.value.filter(row => row.kind === 'SCHOOL' && row.status === 'ACTIVE')
)
const load = async () => {
  roles.value = []
  assignments.value = []
  roleOptions.value = []
  members.value = []
  schools.value = []
  const [rp, ap] = await Promise.all([
    managementPage('roles/page', rolePage.value),
    managementPage('assignments/page', assignmentPage.value)
  ])
  roles.value = rp.records
  roleTotal.value = rp.total
  assignments.value = ap.records
  assignmentTotal.value = ap.total
  roleOptions.value = await managementOptions('roles/page')
  if (store.can('MANAGE_MEMBERS')) members.value = await managementOptions('members/page')
  if (store.can('MANAGE_ORGANIZATIONS'))
    schools.value = await managementOptions('organizations/page')
}
onMounted(() => run(load))
const editRole = (row?: ManagementRecord) => {
  Object.assign(role, {
    id: row?.id || '',
    version: row?.version || '',
    code: row?.code || '',
    name: row?.name || '',
    status: row?.status || 'ACTIVE'
  })
  notice.value = ''
  open.value = 'role'
}
const editAssignment = (row?: ManagementRecord) => {
  Object.assign(assignment, {
    id: row?.id || '',
    version: row?.version || '',
    memberId: row?.memberId || '',
    roleId: row?.roleId || '',
    schoolIds: [...(row?.schoolIds || [])],
    status: row?.status || 'ACTIVE'
  })
  notice.value = ''
  open.value = 'assignment'
}
const save = () =>
  run(async () => {
    const source = open.value === 'role' ? role : assignment
    const command: ManagementCommand =
      open.value === 'role'
        ? {
            mode: role.id ? 'UPDATE' : 'CREATE',
            code: role.code,
            name: role.name,
            status: role.status
          }
        : {
            mode: assignment.id ? 'UPDATE' : 'CREATE',
            memberId: assignment.memberId,
            roleId: assignment.roleId,
            schoolIds: [...assignment.schoolIds],
            status: assignment.status
          }
    if (source.id) {
      command.id = source.id
      command.expectedVersion = source.version
    }
    await managementCall(open.value === 'role' ? 'roles/save' : 'assignments/save', command, true)
    open.value = ''
    await load()
  })
</script>
<template>
  <section>
    <h2>{{ t('enterprise.roles') }}</h2>
    <el-alert
      v-if="notice && !open"
      :title="notice"
      type="warning"
      :closable="false"
      role="alert"
    />
    <el-button type="primary" :disabled="busy" @click="editRole()">{{
      t('enterprise.createRole')
    }}</el-button>
    <el-button :loading="busy" @click="run(load)">{{ t('enterprise.refresh') }}</el-button>
    <el-table :data="roles" row-key="id">
      <el-table-column prop="name" :label="t('enterprise.name')" /><el-table-column
        prop="code"
        :label="t('enterprise.code')"
      /><el-table-column prop="id" label="ID" />
      <el-table-column :label="t('enterprise.status')"
        ><template #default="{ row }">{{
          t(`enterprise.${row.status}`)
        }}</template></el-table-column
      >
      <el-table-column :label="t('enterprise.actions')"
        ><template #default="{ row }"
          ><el-button :disabled="busy" @click="editRole(row)">{{
            t('enterprise.edit')
          }}</el-button></template
        ></el-table-column
      >
    </el-table>
    <el-pagination
      :disabled="busy"
      v-model:current-page="rolePage"
      :page-size="20"
      :total="roleTotal"
      layout="prev, pager, next, total"
      @current-change="run(load)"
    />
    <h3>{{ t('enterprise.assignments') }}</h3>
    <p>{{ t('enterprise.assignmentHint') }}</p>
    <el-button type="primary" :disabled="busy" @click="editAssignment()">{{
      t('enterprise.createAssignment')
    }}</el-button>
    <el-table :data="assignments" row-key="id">
      <el-table-column prop="memberId" :label="t('enterprise.memberId')" /><el-table-column
        prop="roleId"
        :label="t('enterprise.roleId')"
      />
      <el-table-column prop="schoolIds" :label="t('enterprise.schools')" />
      <el-table-column :label="t('enterprise.status')"
        ><template #default="{ row }">{{
          t(`enterprise.${row.status}`)
        }}</template></el-table-column
      >
      <el-table-column :label="t('enterprise.actions')"
        ><template #default="{ row }"
          ><el-button :disabled="busy" @click="editAssignment(row)">{{
            t('enterprise.edit')
          }}</el-button></template
        ></el-table-column
      >
    </el-table>
    <el-pagination
      :disabled="busy"
      v-model:current-page="assignmentPage"
      :page-size="20"
      :total="assignmentTotal"
      layout="prev, pager, next, total"
      @current-change="run(load)"
    />
    <el-dialog
      :model-value="!!open"
      :title="t(`enterprise.${open === 'role' ? 'role' : 'assignments'}`)"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      width="560px"
      @update:model-value="
        value => {
          if (!value) open = ''
        }
      "
    >
      <el-alert v-if="notice" :title="notice" type="warning" :closable="false" role="alert" />
      <el-form label-position="top" @submit.prevent="save">
        <template v-if="open === 'role'">
          <el-form-item :label="t('enterprise.code')"
            ><el-input v-model="role.code" name="roleCode" :disabled="!!role.id" maxlength="64"
          /></el-form-item>
          <el-form-item :label="t('enterprise.name')"
            ><el-input v-model="role.name" name="roleName" maxlength="128"
          /></el-form-item>
          <el-form-item :label="t('enterprise.status')"
            ><el-select v-model="role.status"
              ><el-option value="ACTIVE" :label="t('enterprise.ACTIVE')" /><el-option
                value="DISABLED"
                :label="t('enterprise.DISABLED')" /></el-select
          ></el-form-item>
        </template>
        <template v-else>
          <el-form-item :label="t('enterprise.memberId')"
            ><el-select
              v-model="assignment.memberId"
              filterable
              :allow-create="!store.can('MANAGE_MEMBERS')"
              :disabled="!!assignment.id"
              ><el-option
                v-for="member in members"
                :key="member.id"
                :value="member.id"
                :label="`${member.displayName || member.userId} (${member.id})`" /></el-select
          ></el-form-item>
          <el-form-item :label="t('enterprise.role')"
            ><el-select v-model="assignment.roleId" :disabled="!!assignment.id"
              ><el-option
                v-for="item in roleOptions"
                :key="item.id"
                :value="item.id"
                :label="item.name" /></el-select
          ></el-form-item>
          <el-form-item :label="t('enterprise.schools')"
            ><el-select
              v-model="assignment.schoolIds"
              multiple
              filterable
              :allow-create="!store.can('MANAGE_ORGANIZATIONS')"
              ><el-option
                v-for="school in selectableSchools"
                :key="school.id"
                :value="school.id"
                :label="school.name" /></el-select
          ></el-form-item>
          <el-form-item :label="t('enterprise.status')"
            ><el-select v-model="assignment.status"
              ><el-option value="ACTIVE" :label="t('enterprise.ACTIVE')" /><el-option
                value="DISABLED"
                :label="t('enterprise.DISABLED')" /></el-select
          ></el-form-item>
        </template>
        <el-button native-type="submit" type="primary" :loading="busy">{{
          t('enterprise.save')
        }}</el-button>
        <el-button :disabled="busy" @click="open = ''">{{ t('enterprise.cancel') }}</el-button>
      </el-form>
    </el-dialog>
  </section>
</template>
