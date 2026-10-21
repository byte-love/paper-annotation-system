<template>
  <div class="upload-external">
    <h2>上传外网</h2>
    <p class="page-desc">
      将已标注完成的论文全量推送到外网 150 服务器。
      上传后论文<b>仍留在论文列表</b>，标记为「已上传」，需要改关联数据库时可随时打回标注。
    </p>

    <!-- 操作按钮 -->
    <div class="action-row" v-if="!uploaded">
      <el-button type="primary" :loading="loading" @click="loadPreview">
        <el-icon><Search /></el-icon> 加载待上传数据
      </el-button>
      <el-button
        v-if="previewLoaded"
        type="success"
        :loading="uploading"
        :disabled="!hasChanges"
        @click="confirmUpload"
      >
        <el-icon><Upload /></el-icon> 上传到外网
      </el-button>
    </div>

    <!-- 加载中 -->
    <el-empty v-if="!previewLoaded && !loading" description="点击「加载待上传数据」预览本次新增或改动的论文" />

    <!-- 预览结果 -->
    <div v-if="previewLoaded">
      <!-- 汇总 -->
      <el-alert
        :title="totalNew > 0
          ? `本次新增/改动 ${totalNew} 篇待上传（分布在 ${activeCollections.length} 个集合中）`
          : (totalDeletedPending > 0
            ? `没有新增或改动的论文，但有 ${totalDeletedPending} 篇已删除的还留在 150 上`
            : '没有新增或改动的论文，无需上传')"
        :type="hasChanges ? 'success' : 'info'"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
      >
        <template v-if="totalUploadedAlready > 0">
          已上传过 {{ totalUploadedAlready }} 篇；本次为<b>全量覆盖推送</b>，会把这些论文的最新内容一起推过去。
        </template>
        <div v-if="totalDeletedPending > 0" style="margin-top: 4px;">
          🗑 已删除 <b>{{ totalDeletedPending }}</b> 篇（曾上传过外网）。150 上现在还留着它们，
          本次全量覆盖会一并抹掉。
        </div>
      </el-alert>

      <!-- 各集合详情 -->
      <el-collapse v-model="activeNames">
        <el-collapse-item
          v-for="s in changedCollections"
          :key="s.collection"
          :name="s.collection"
        >
          <template #title>
            <div style="display: flex; align-items: center; gap: 12px;">
              <el-tag>{{ s.label }}</el-tag>
              <span style="color: #909399;">→ 150 表:</span>
              <code>{{ s.table150 }}</code>
              <el-tag v-if="s.newCount > 0" type="success" effect="plain">{{ s.newCount }} 条待上传</el-tag>
              <el-tag v-if="s.deletedPending > 0" type="danger" effect="plain">
                🗑 待移除 {{ s.deletedPending }} 条
              </el-tag>
              <el-tag v-if="s.uploadedCount > 0" type="info" effect="plain">
                已上传 {{ s.uploadedCount }} 条
              </el-tag>
            </div>
          </template>

          <!-- 样例表格 -->
          <el-table :data="s.samples" border size="small" max-height="300">
            <el-table-column type="index" label="#" width="40" />
            <el-table-column prop="title" label="论文标题" min-width="260" show-overflow-tooltip />
            <el-table-column prop="journalName" label="期刊" width="160" show-overflow-tooltip />
            <el-table-column prop="publishDate" label="发表日期" width="130" />
            <el-table-column prop="relateDBs" label="关联数据库" width="180">
              <template #default="{ row }">
                <el-tag v-if="row.relateDBs" size="small" type="success">{{ row.relateDBs }}</el-tag>
                <el-tag v-else size="small" type="info">无关联</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.dirty" size="small" type="warning">改动</el-tag>
                <el-tag v-else size="small" type="primary" effect="plain">新增</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="s.newCount > 5" style="color: #909399; font-size: 12px; margin-top: 6px;">
            仅展示前 5 条样例，共 {{ s.newCount }} 条
          </div>
        </el-collapse-item>
      </el-collapse>

      <!-- 空集合（无待上传数据） -->
      <div v-if="emptyCollections.length > 0" style="margin-top: 12px; color: #909399; font-size: 13px;">
        以下集合无待上传数据：
        <el-tag
          v-for="s in emptyCollections"
          :key="s.collection"
          size="small"
          type="info"
          style="margin: 0 4px;"
        >{{ s.label }}</el-tag>
      </div>
    </div>

    <!-- 上传成功结果 -->
    <div v-if="uploaded">
      <el-alert title="上传完成！" type="success" :closable="false" show-icon style="margin-bottom: 16px;">
        共上传 {{ uploadResult.totalUploaded }} 条数据到外网 150 服务器。
        论文仍在论文列表中，已标记「已上传」。
      </el-alert>

      <el-table :data="uploadResult.perCollection" border size="small">
        <el-table-column label="集合" width="180">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'">{{ row.small }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="oldCount" label="归档兜底" width="90" />
        <el-table-column prop="newCount" label="工作集合" width="90" />
        <el-table-column prop="pendingCount" label="本次新增/改动" width="110" />
        <el-table-column prop="uploaded" label="上传 150" width="90" />
        <el-table-column prop="table150" label="150 表名" width="260">
          <template #default="{ row }">
            <code>{{ row.table150 }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="200">
          <template #default="{ row }">
            <el-tag v-if="row.success" type="success" size="small">成功</el-tag>
            <span v-else style="color: #f56c6c; font-size: 12px;">{{ row.message }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-button type="primary" style="margin-top: 16px;" @click="resetPage">重新加载</el-button>
    </div>

    <!-- 历史数据回补（一次性维护操作） -->
    <el-divider content-position="left">
      <span class="section-title">历史数据回补</span>
    </el-divider>
    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px;">
      v3.0 的上传逻辑会把论文从论文列表迁走。如果发现列表里缺少已上传过的论文，
      用这里把它们找回来——归档数据会写回论文列表并标记「已上传」，之后就能打回标注修改关联数据库。
      归档数据都是人工审核过的，其中没有关联数据库的会标记为<b>已确认无关联</b>，不会进「数据库标注」待办。
      重复执行不会产生重复数据。
    </el-alert>
    <div class="action-row">
      <el-button :loading="backfilling" @click="runBackfill(true)">
        <el-icon><Search /></el-icon> 先试算（不写入）
      </el-button>
      <el-button
        v-if="backfillResult && (backfillResult.totalInserted > 0 || backfillResult.totalRepaired > 0)"
        type="warning"
        :loading="backfilling"
        @click="confirmBackfill"
      >
        <el-icon><Upload /></el-icon> 执行回补（写入 {{ backfillResult.totalInserted }} 条<span v-if="backfillResult.totalRepaired > 0">，修正 {{ backfillResult.totalRepaired }} 条</span>）
      </el-button>
    </div>

    <el-table v-if="backfillResult" :data="backfillResult.details" border size="small">
      <el-table-column prop="label" label="分类" width="140" />
      <el-table-column prop="archive" label="归档集合" min-width="240">
        <template #default="{ row }"><code>{{ row.archive }}</code></template>
      </el-table-column>
      <el-table-column prop="archiveCount" label="归档条数" width="100" />
      <el-table-column :label="backfillResult.dryRun ? '可回补' : '已回补'" width="100">
        <template #default="{ row }">
          <span :style="row.willInsert > 0 ? 'color:#67c23a;font-weight:bold' : ''">{{ row.willInsert }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="skipped" label="已存在跳过" width="110" />
      <el-table-column :label="backfillResult.dryRun ? '待修正无关联' : '已修正无关联'" width="120">
        <template #default="{ row }">
          <span :style="row.repaired > 0 ? 'color:#e6a23c;font-weight:bold' : ''">{{ row.repaired }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="160">
        <template #default="{ row }">
          <el-tag v-if="row.success" type="success" size="small">
            {{ backfillResult.dryRun ? '试算完成' : '完成' }}
          </el-tag>
          <span v-else style="color:#f56c6c;font-size:12px">{{ row.message }}</span>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Upload } from '@element-plus/icons-vue'
import { getUploadPreview, executeUpload, backfillFromArchive } from '../api/paper'

const loading = ref(false)
const uploading = ref(false)
const previewLoaded = ref(false)
const uploaded = ref(false)
const summaries = ref([])
const uploadResult = ref({ totalUploaded: 0, perCollection: [] })
const activeNames = ref([])

const totalNew = computed(() => summaries.value.reduce((n, s) => n + s.newCount, 0))
const totalUploadedAlready = computed(() =>
  summaries.value.reduce((n, s) => n + (s.uploadedCount || 0), 0)
)
// 已删除但 150 上还留着的：全量覆盖推送才会把它们抹掉，
// 所以只删了论文、没有新增标注时也得允许上传
const totalDeletedPending = computed(() =>
  summaries.value.reduce((n, s) => n + (s.deletedPending || 0), 0)
)
const hasChanges = computed(() => totalNew.value > 0 || totalDeletedPending.value > 0)
const activeCollections = computed(() => summaries.value.filter(s => s.newCount > 0))
// 有新增/改动，或有已删除待从 150 移除的，都算本次有变化
const changedCollections = computed(() =>
  summaries.value.filter(s => s.newCount > 0 || s.deletedPending > 0)
)
const emptyCollections = computed(() =>
  summaries.value.filter(s => s.newCount === 0 && !s.deletedPending)
)

async function loadPreview() {
  loading.value = true
  try {
    const { data } = await getUploadPreview()
    summaries.value = data.summaries
    // 默认展开所有有数据的集合
    activeNames.value = data.summaries
      .filter(s => s.newCount > 0 || s.deletedPending > 0)
      .map(s => s.collection)
    previewLoaded.value = true
    uploaded.value = false
  } catch (e) {
    ElMessage.error('加载预览失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

async function confirmUpload() {
  try {
    await ElMessageBox.confirm(
      `确定把本次新增/改动的 ${totalNew.value} 篇论文，连同已上传过的数据全量推送到外网 150 服务器？\n\n` +
      (totalDeletedPending.value > 0
        ? `另有 ${totalDeletedPending.value} 篇已删除的论文，本次推送会把它们从 150 上移除。\n\n`
        : '') +
      '上传后：\n' +
      '1. 150 上原有表会先备份为 ××00 表\n' +
      '2. 论文仍留在论文列表，标记为「已上传」，不会消失\n' +
      '3. 要改已上传论文的关联数据库，在论文列表点「打回标注」即可\n' +
      '4. 排序规则：publishDate 降序 + 有关联数据库排前面',
      '确认上传外网',
      { type: 'warning', confirmButtonText: '确认上传', cancelButtonText: '取消' }
    )
  } catch { return }

  uploading.value = true
  try {
    const { data } = await executeUpload()
    uploadResult.value = data
    uploaded.value = true
    if (data.success) {
      ElMessage.success(`上传完成，共 ${data.totalUploaded} 条`)
    } else {
      ElMessage.error(data.message || '上传失败')
    }
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.response?.data?.message || e.message))
  } finally {
    uploading.value = false
  }
}

function resetPage() {
  previewLoaded.value = false
  uploaded.value = false
  summaries.value = []
  uploadResult.value = { totalUploaded: 0, perCollection: [] }
}

// ==================== 历史数据回补 ====================

const backfilling = ref(false)
const backfillResult = ref(null)

async function runBackfill(dryRun) {
  backfilling.value = true
  try {
    const { data } = await backfillFromArchive(dryRun)
    backfillResult.value = data
    const fix = data.totalRepaired > 0 ? `，另有 ${data.totalRepaired} 条无关联标记待修正` : ''
    if (dryRun) {
      ElMessage.info(data.totalInserted > 0 || data.totalRepaired > 0
        ? `试算完成：可回补 ${data.totalInserted} 条，已存在跳过 ${data.totalSkipped} 条${fix}`
        : '试算完成：没有需要回补的数据')
    } else {
      ElMessage.success(data.totalRepaired > 0
        ? `回补完成，写回 ${data.totalInserted} 条，修正 ${data.totalRepaired} 条无关联标记`
        : `回补完成，写回 ${data.totalInserted} 条，这些论文已回到论文列表`)
    }
  } catch (e) {
    ElMessage.error('回补失败: ' + (e.response?.data?.message || e.message))
  } finally {
    backfilling.value = false
  }
}

async function confirmBackfill() {
  try {
    await ElMessageBox.confirm(
      `确定把 ${backfillResult.value.totalInserted} 条归档数据写回论文列表？\n\n` +
      '写入内容：字段转成论文列表格式，标记为「已上传」。\n' +
      '归档数据都是人工审核过的，没有关联数据库的会标记为「已确认无关联」，' +
      '不会进「数据库标注」待办队列。\n' +
      (backfillResult.value.totalRepaired > 0
        ? `同时修正 ${backfillResult.value.totalRepaired} 条已回补论文的无关联标记。\n`
        : '') +
      '归档集合保持原样不动，不会删除任何数据。',
      '确认执行回补',
      { type: 'warning', confirmButtonText: '确认写入', cancelButtonText: '取消' }
    )
  } catch { return }
  await runBackfill(false)
}
</script>

<style scoped>
.upload-external h2 { margin-bottom: 4px; }
.page-desc {
  color: #909399;
  font-size: 13px;
  margin-bottom: 20px;
}
.action-row {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}
code {
  background: #f5f5f5;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
}
.section-title {
  font-weight: bold;
  color: #606266;
}
.el-divider { margin-top: 32px; }
</style>
