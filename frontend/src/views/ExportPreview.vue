<template>
  <div>
    <h2 style="margin-bottom: 16px;">导出 Excel</h2>

    <!-- 选择分类 -->
    <div style="display: flex; gap: 12px; margin-bottom: 16px; align-items: center;">
      <span>选择分类：</span>
      <el-select v-model="currentCollection" placeholder="选择分类" @change="onCollectionChange" style="width: 300px;">
        <el-option v-for="c in collections" :key="c.name"
          :label="`${collectionLabel(c.name)} (${c.count}条)`"
          :value="c.name" />
      </el-select>
      <el-button type="primary" @click="handleExport" :loading="exporting" :disabled="total === 0">
        <el-icon><Download /></el-icon>
        <span style="margin-left: 4px;">导出 Excel</span>
      </el-button>
    </div>

    <!-- 预览表格 —— 这就是预览 -->
    <el-table :data="papers" border stripe v-loading="loading" style="width: 100%;" empty-text="暂无数据，请确认已导入论文">
      <el-table-column type="index" label="#" width="50" />
      <el-table-column prop="title" label="PaperTitle" min-width="260" show-overflow-tooltip />
      <el-table-column prop="journalName" label="JournalName" min-width="160" show-overflow-tooltip />
      <el-table-column v-if="isInternational" prop="journalAbbName" label="JournalAbnName" width="110" />
      <el-table-column prop="publishDate" label="PublishDate" width="140" />
      <el-table-column prop="releaseDate" label="ReleaseDate" width="130" />
      <el-table-column label="Content" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          {{ stripHtml(row.content) }}
        </template>
      </el-table-column>
      <el-table-column v-if="isInternational" prop="type" label="Type" width="100" />
      <el-table-column label="RelateDBs" min-width="180">
        <template #default="{ row }">
          <template v-if="row.relateDBs">
            <el-tag v-for="db in row.relateDBs.split(',')" :key="db" size="small" type="success" style="margin: 2px;">
              {{ db.trim() }}
            </el-tag>
          </template>
          <span v-else style="color: #909399;">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="paperId" label="PaperID" min-width="160" show-overflow-tooltip />
    </el-table>

    <!-- 分页 -->
    <div style="display: flex; justify-content: flex-end; margin-top: 16px;">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadData"
        @current-change="loadData"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { getCollections, getExportPreview, exportPapers } from '../api/paper'

const COLLECTION_LABELS = {
  domestic_papers: '国内论文 (DomesticPaper)',
  intl_chinese_accounting: '国际中文-会计 (InternationalChinesePaper_Accounting)',
  intl_chinese_finance: '国际中文-金融 (InternationalChinesePaper_Finance)',
  intl_accounting: '国际-会计 (Internationalpaper_Accounting)',
  intl_finance: '国际-金融 (Internationalpaper_Finance)'
}
const collectionLabel = name => COLLECTION_LABELS[name] || name

const collections = ref([])
const papers = ref([])
const currentCollection = ref('domestic_papers')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)

const isInternational = computed(() =>
  currentCollection.value !== 'domestic_papers'
)

onMounted(async () => {
  try {
    const res = await getCollections()
    collections.value = res.data.filter(c =>
      c.name !== 'datahub_databases' && COLLECTION_LABELS[c.name]
    )
  } catch (e) {
    console.error('获取集合列表失败', e)
  }
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const res = await getExportPreview(currentCollection.value, currentPage.value - 1, pageSize.value)
    papers.value = res.data.data
    total.value = res.data.total
  } catch (e) {
    console.error('加载预览数据失败', e)
    ElMessage.error('加载数据失败：' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

function onCollectionChange() {
  currentPage.value = 1
  loadData()
}

async function handleExport() {
  exporting.value = true
  try {
    const res = await exportPapers(currentCollection.value)
    const url = window.URL.createObjectURL(new Blob([res.data]))
    const link = document.createElement('a')
    link.href = url
    const disposition = res.headers['content-disposition']
    let filename = 'export.xlsx'
    if (disposition) {
      const match = disposition.match(/filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/)
      if (match && match[1]) {
        filename = match[1].replace(/['"]/g, '')
      }
    }
    link.setAttribute('download', filename)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success(`已导出：${filename}`)
  } catch (e) {
    ElMessage.error('导出失败')
  } finally {
    exporting.value = false
  }
}

function stripHtml(html) {
  if (!html) return ''
  const text = html.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()
  return text.length > 150 ? text.substring(0, 150) + '…' : text
}
</script>
