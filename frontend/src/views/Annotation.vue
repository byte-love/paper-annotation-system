<template>
  <div class="annotation-page">
    <!-- 顶部：五个分类页签（带待办数） -->
    <el-tabs v-model="activeCollection" type="border-card" @tab-change="onTabChange">
      <el-tab-pane
        v-for="c in collections"
        :key="c.value"
        :name="c.value"
      >
        <template #label>
          <span>{{ c.label }}</span>
          <el-badge
            v-if="counts[c.value] > 0"
            :value="counts[c.value]"
            :max="999"
            class="tab-badge"
          />
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 本批标注结束 -->
    <el-empty
      v-if="!loading && finished"
      :description="`本次标注结束 🎉 共标注 ${papers.length} 篇`"
    >
      <el-button @click="finished = false">← 返回查看本批</el-button>
      <el-button type="primary" @click="loadPapers">继续标注下一批</el-button>
    </el-empty>

    <!-- 全部处理完 -->
    <el-empty
      v-else-if="!loading && papers.length === 0"
      description="该分类下的论文已全部标注完成 🎉"
    />

    <!-- 主体：左论文 / 右数据库 -->
    <div v-else class="main-area" v-loading="loading">
      <!-- 左：论文展示 -->
      <el-card class="paper-panel" shadow="never">
        <div class="progress-bar">
          <!-- x=最近一次标注推进到的篇数，点上一篇返回修改不会回退 -->
          <el-tag type="success" effect="plain">已标注 {{ frontier }} / {{ papers.length }} 篇</el-tag>
          <!-- 论文跳转列表：直接跳到任意已标注的论文修改，不用一直点上一篇 -->
          <el-select
            :model-value="currentIndex"
            filterable
            size="small"
            class="paper-jump"
            placeholder="跳转到某一篇..."
            @change="jumpTo"
          >
            <el-option
              v-for="(p, idx) in papers"
              :key="p.id"
              :value="idx"
              :disabled="idx > frontier"
              :label="`第 ${idx + 1} 篇 ${idx < frontier ? '✅' : '⭕'} ${p.title}`"
            />
          </el-select>
          <el-button
            v-if="currentIndex < frontier && frontier < papers.length"
            size="small"
            type="warning"
            plain
            @click="currentIndex = frontier"
          >回到最近未标注（第 {{ frontier + 1 }} 篇）</el-button>
          <el-tag v-if="currentPaper.reAnnotate" type="danger" effect="dark">🔄 重新标注</el-tag>
          <el-tag v-if="currentPaper.journalAbbName" effect="plain">{{ currentPaper.journalAbbName }}</el-tag>
          <el-tag v-if="currentPaper.type" type="info" effect="plain">{{ currentPaper.type }}</el-tag>
        </div>

        <h2 class="paper-title">{{ currentPaper.title }}</h2>

        <div class="paper-meta">
          <span class="journal">{{ currentPaper.journalName }}</span>
          <el-divider direction="vertical" />
          <span>{{ currentPaper.publishDate }}</span>
        </div>
        <div class="paper-authors" v-if="currentPaper.authors">
          👤 {{ currentPaper.authors }}
        </div>

        <el-divider content-position="left">摘要</el-divider>
        <p class="paper-abstract">{{ currentPaper.abstractText || '（无摘要）' }}</p>

        <template v-if="currentPaper.keywords">
          <el-divider content-position="left">关键词</el-divider>
          <div class="paper-keywords">
            <el-tag
              v-for="kw in splitKeywords(currentPaper.keywords)"
              :key="kw"
              size="small"
              effect="light"
              class="kw-tag"
            >{{ kw }}</el-tag>
          </div>
        </template>

        <div class="paper-link" v-if="currentPaper.sourceUrl">
          <el-link :href="currentPaper.sourceUrl" target="_blank" type="primary">🔗 查看原文</el-link>
        </div>
      </el-card>

      <!-- 右：DataHub 数据库勾选 -->
      <el-card class="db-panel" shadow="never">
        <DbSelector v-model="selectedIds" max-height="none" class="db-selector-fill" />

        <!-- 操作按钮：勾选了→保存关联；没勾选→确认无关联，均计入已标注 -->
        <div class="action-bar">
          <!-- 标注时发现抓取的数据不合要求，直接删掉，自动跳下一篇 -->
          <el-button
            type="danger"
            plain
            :disabled="saving || deleting"
            :loading="deleting"
            @click="deleteCurrent"
          >🗑 删除这篇</el-button>
          <div class="action-bar-right">
            <el-button :disabled="currentIndex === 0 || saving" @click="prevPaper">← 上一篇</el-button>
            <el-button
              type="primary"
              :loading="saving"
              @click="nextPaper"
            >{{ currentIndex >= papers.length - 1 ? '✓ 完成标注' : '下一篇 →' }}</el-button>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPendingCounts, getPendingPapers, getPapersByIds, saveAnnotation, deletePapers } from '../api/paper'
import { useDataHubDb } from '../composables/useDataHubDb'
import DbSelector from '../components/DbSelector.vue'

// 五个业务分类（与 MongoDB 集合一一对应）
const collections = [
  { value: 'domestic_papers', label: '国内论文' },
  { value: 'intl_chinese_accounting', label: '国际中文-会计' },
  { value: 'intl_chinese_finance', label: '国际中文-金融' },
  { value: 'intl_accounting', label: '国际-会计' },
  { value: 'intl_finance', label: '国际-金融' }
]

const { dbName } = useDataHubDb()

const activeCollection = ref(collections[0].value)
const counts = ref({})
const papers = ref([])            // 本批次论文（加载后固定为 n 篇，标完前可随时返回修改）
const currentIndex = ref(0)
const selectedIds = ref([])       // 当前论文勾选的数据库 ID
const frontier = ref(0)           // 标注前沿：第一篇未标注论文的下标（之前的都已保存）
const finished = ref(false)       // 本批次 n 篇是否已全部标完
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)

const currentPaper = computed(() => papers.value[currentIndex.value] || {})

// 切换论文时：重新标注的论文预勾选原有值，其余从空开始
watch(currentPaper, p => {
  selectedIds.value = p.relateDBs
    ? p.relateDBs.split(',').map(s => s.trim()).filter(Boolean)
    : []
})

const splitKeywords = kw => kw.split(/[;；,，]/).map(s => s.trim()).filter(Boolean)

async function loadCounts() {
  const { data } = await getPendingCounts()
  counts.value = data
}

// ===== 批次快照：一轮 n 篇标完之前，切模块/关页面回来批次总数 m 不变 =====
const batchKey = () => `annotation_batch_${activeCollection.value}`

function saveBatch() {
  localStorage.setItem(batchKey(), JSON.stringify({
    ids: papers.value.map(p => p.id),
    frontier: frontier.value
  }))
}

function clearBatch() {
  localStorage.removeItem(batchKey())
}

async function loadPapers() {
  loading.value = true
  try {
    const { data } = await getPendingPapers(activeCollection.value)
    let list = data.data
    let start = 0

    // 有未完成的批次快照则恢复：批次内已标注的论文按 id 回取
    let saved = null
    try { saved = JSON.parse(localStorage.getItem(batchKey())) } catch { /* 快照损坏则忽略 */ }
    if (saved && Array.isArray(saved.ids) && saved.ids.length > 0 && saved.frontier < saved.ids.length) {
      const { data: byIds } = await getPapersByIds(activeCollection.value, saved.ids)

      // 快照按 id 直取，取回来的论文可能早已不需要标注了（比如在别处确认了
      // 无关联、或被回补标成已上传）。只保留两类：
      //   1. 本批次自己已标过的（下标 < frontier），留着供返回修改
      //   2. 仍在当前待标注列表里的
      // 否则旧快照会把已处理完的论文一直挂在页面上，和角标数量对不上。
      const pendingIds = new Set(list.map(p => p.id))
      const batch = byIds.data.filter((p, idx) => idx < saved.frontier || pendingIds.has(p.id))
      const dropped = byIds.data.length - batch.length

      if (batch.length > 0) {
        const inBatch = new Set(batch.map(p => p.id))
        // 批次进行中导入的新论文追加到末尾（m = 原批次 + 新增）
        const appended = list.filter(p => !inBatch.has(p.id))
        list = [...batch, ...appended]
        // frontier 按过滤后实际保留的已标注条数重算
        start = Math.min(saved.frontier, batch.length)
      } else if (dropped > 0) {
        // 整批都已失效，丢弃快照，按当前待标注列表重新开批
        clearBatch()
      }
    }

    papers.value = list
    frontier.value = start
    // 恢复批次时直接定位到标注前沿，接着上次的进度标
    currentIndex.value = Math.min(start, Math.max(0, list.length - 1))
    finished.value = false
    if (list.length > 0) saveBatch()
    else clearBatch()
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  loadPapers()
}

function prevPaper() {
  if (currentIndex.value > 0) currentIndex.value--
}

/** 跳转到列表中选中的论文（只能跳到已标注的或标注前沿，不能跳过未标注的） */
function jumpTo(idx) {
  if (idx <= frontier.value) currentIndex.value = idx
}

/**
 * 下一篇 = 保存 + 翻页：
 * 勾选了数据库→保存关联；没勾选→确认无关联（noRelateDbs）。
 * 论文保留在本批次列表中，可返回修改；最后一篇保存后本次标注结束。
 */
async function nextPaper() {
  const paper = currentPaper.value
  const noRelateDbs = selectedIds.value.length === 0
  const relateDBs = selectedIds.value.join(',')
  saving.value = true
  try {
    await saveAnnotation(activeCollection.value, paper.id, relateDBs, noRelateDbs)
    // 本地同步保存结果，返回该篇时正确回显勾选状态
    paper.relateDBs = relateDBs
    paper.noRelateDbs = noRelateDbs
    paper.reAnnotate = false
    frontier.value = Math.max(frontier.value, currentIndex.value + 1)
    ElMessage.success(noRelateDbs ? '已确认无关联数据库' : `已保存：${selectedIds.value.map(dbName).join('、')}`)
    loadCounts()
    if (currentIndex.value < papers.value.length - 1) {
      currentIndex.value++
      saveBatch()          // 持久化批次进度
    } else {
      finished.value = true
      clearBatch()         // n = m，本轮结束，下次进入重新开批
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

/**
 * 删除当前论文（软删除）：从本批次移除并停在同一下标上，即自动展示下一篇。
 * 标注过程中发现抓取的数据不合要求时用，删错了去论文列表的回收站恢复。
 */
async function deleteCurrent() {
  const paper = currentPaper.value
  try {
    await ElMessageBox.confirm(
      `确定删除《${paper.title}》吗？删除后会从论文列表和标注待办中消失，可以在「论文浏览」的回收站恢复。` +
      (paper.uploaded ? '这篇已上传到外网，150 上的数据要等下次点「上传外网」才会同步删掉。' : ''),
      '删除论文',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }

  deleting.value = true
  try {
    await deletePapers(activeCollection.value, [paper.id])

    // 从本批次移除。删掉的是已标注过的（下标 < frontier）就把前沿也往回收一格，
    // 否则"已标注 x / y"里的 x 会把删掉的那篇也算进去。
    const idx = currentIndex.value
    papers.value.splice(idx, 1)
    if (idx < frontier.value) frontier.value--

    ElMessage.success('已删除，可在「论文浏览」的回收站恢复')
    loadCounts()

    if (papers.value.length === 0) {
      // 本批空了，重新开一批
      clearBatch()
      await loadPapers()
      return
    }
    // 停在原下标即自动展示下一篇；删的是最后一篇则退一格
    currentIndex.value = Math.min(idx, papers.value.length - 1)
    // 删完之后整批都标完了，直接收尾
    if (frontier.value >= papers.value.length) {
      finished.value = true
      clearBatch()
    } else {
      saveBatch()
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除失败')
  } finally {
    deleting.value = false
  }
}

onMounted(async () => {
  await loadCounts()
  await loadPapers()
})
</script>

<style scoped>
.annotation-page {
  height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
}

.tab-badge {
  margin-left: 6px;
  transform: translateY(-2px);
}

.main-area {
  flex: 1;
  display: flex;
  gap: 16px;
  margin-top: 16px;
  min-height: 0;
}

/* 左：论文 */
.paper-panel {
  flex: 1.1;
  overflow-y: auto;
}

.progress-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.paper-jump {
  width: 320px;
}

.paper-title {
  font-size: 20px;
  line-height: 1.5;
  margin-bottom: 12px;
  color: #303133;
}

.paper-meta {
  color: #606266;
  margin-bottom: 8px;
}

.paper-meta .journal {
  color: #409eff;
  font-weight: bold;
}

.paper-authors {
  color: #606266;
  margin-bottom: 4px;
}

.paper-abstract {
  line-height: 1.9;
  color: #303133;
  text-align: justify;
  white-space: pre-wrap;
}

.paper-keywords .kw-tag {
  margin: 0 6px 6px 0;
}

.paper-link {
  margin-top: 16px;
}

/* 右：数据库勾选 */
.db-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.db-panel :deep(.el-card__body) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.db-selector-fill {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.db-selector-fill :deep(.db-scroll) {
  flex: 1;
  min-height: 0;
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid #ebeef5;
  margin-top: 10px;
}

.action-bar-right {
  display: flex;
  gap: 10px;
}
</style>
