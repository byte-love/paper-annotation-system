<template>
  <div class="db-selector">
    <div class="db-toolbar">
      <el-input
        v-model="search"
        placeholder="🔍 搜索数据库名称或 ID，如：专利 / CPTD"
        clearable
      />
      <el-tag
        v-if="modelValue.length"
        :type="modelValue.length >= maxSelect ? 'warning' : 'success'"
        class="selected-count"
      >
        已选 {{ modelValue.length }} / {{ maxSelect }} 个{{ modelValue.length >= maxSelect ? '（已达上限）' : '' }}
      </el-tag>
    </div>

    <!-- 已选展示区（可点 × 取消） -->
    <div class="selected-area" v-if="modelValue.length">
      <el-tag
        v-for="id in modelValue"
        :key="id"
        closable
        type="success"
        class="sel-tag"
        @close="remove(id)"
      >{{ dbName(id) }}</el-tag>
    </div>

    <el-scrollbar :max-height="maxHeight" class="db-scroll">
      <el-checkbox-group
        :model-value="modelValue"
        :max="maxSelect"
        @update:model-value="v => emit('update:modelValue', v)"
      >
        <div v-for="group in filteredGroups" :key="group.partition" class="db-group">
          <div class="db-group-title">{{ group.partition }}<span class="group-count">{{ group.databases.length }} 个</span></div>
          <el-checkbox
            v-for="db in group.databases"
            :key="db.id"
            :value="db.id"
            class="db-checkbox"
          >{{ db.name }}<span class="db-id">{{ db.id }}</span></el-checkbox>
        </div>
        <el-empty v-if="search && filteredGroups.length === 0" description="没有匹配的数据库" :image-size="60" />
      </el-checkbox-group>
    </el-scrollbar>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useDataHubDb } from '../composables/useDataHubDb'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },   // 勾选的数据库 ID 数组
  maxHeight: { type: String, default: '52vh' },
  maxSelect: { type: Number, default: 4 }           // 每篇论文最多关联的数据库数（与后端校验一致）
})
const emit = defineEmits(['update:modelValue'])

const { groups, load, dbName } = useDataHubDb()
const search = ref('')

// 搜索过滤（名称或 ID 模糊匹配，保持分组结构）
const filteredGroups = computed(() => {
  const kw = search.value.trim().toLowerCase()
  if (!kw) return groups.value
  return groups.value
    .map(g => ({
      partition: g.partition,
      databases: g.databases.filter(
        db => db.name.toLowerCase().includes(kw) || db.id.toLowerCase().includes(kw)
      )
    }))
    .filter(g => g.databases.length > 0)
})

function remove(id) {
  emit('update:modelValue', props.modelValue.filter(x => x !== id))
}

onMounted(load)
</script>

<style scoped>
.db-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 8px;
}

.selected-count {
  flex-shrink: 0;
}

.group-count {
  float: right;
  font-weight: normal;
  font-size: 12px;
  color: #a0cfff;
}

.selected-area {
  padding: 8px 8px 2px;
  background: #f0f9eb;
  border-radius: 6px;
  margin-bottom: 8px;
  max-height: 90px;
  overflow-y: auto;
}

.sel-tag {
  margin: 0 6px 6px 0;
}

.db-group {
  margin-bottom: 6px;
}

.db-group-title {
  /* 必须显式声明字号：el-checkbox-group 全局样式是 font-size:0，
     裸文本会继承成 0px 导致分类名不可见（v2.1 修复） */
  font-size: 14px;
  line-height: 1.6;
  font-weight: bold;
  color: #409eff;
  background: #ecf5ff;
  padding: 6px 10px;
  border-radius: 4px;
  margin: 8px 0 4px;
  position: sticky;
  top: 0;
  z-index: 1;
}

.db-checkbox {
  width: 48%;
  margin: 2px 0;
  padding-left: 10px;
}

.db-id {
  color: #c0c4cc;
  font-size: 12px;
  margin-left: 4px;
}
</style>
