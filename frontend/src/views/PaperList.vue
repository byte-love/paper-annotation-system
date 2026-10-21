<template>
  <div class="paper-list">
    <h2>📚 论文浏览</h2>

    <!-- 集合选择 + 操作 -->
    <el-card class="search-card">
      <el-row :gutter="12" align="middle">
        <el-col :span="6">
          <el-select v-model="currentCollection" placeholder="选择集合" @change="onCollectionChange" style="width:100%">
            <el-option
              v-if="collections.length"
              :label="`全部论文 (${totalAllCount}条)`"
              value="all"
            />
            <el-option
              v-for="c in collections"
              :key="c.name"
              :label="`${collectionLabel(c.name)} (${c.count}条)`"
              :value="c.name"
            />
          </el-select>
        </el-col>
        <el-col :span="12">
          <el-button v-if="!trashMode" type="primary" :disabled="!currentCollection" @click="searchVisible = true">
            <el-icon><Search /></el-icon>&nbsp;高级搜索
          </el-button>
          <el-button v-if="searchMode && !trashMode" @click="resetSearch">清除搜索</el-button>
          <el-button
            v-if="!trashMode"
            type="warning"
            :disabled="selectedRows.length === 0"
            @click="batchReAnnotate"
          >🔄 批量送回标注（{{ selectedRows.length }}）</el-button>
          <el-button
            v-if="!trashMode"
            type="danger"
            :disabled="selectedRows.length === 0"
            @click="batchDelete"
          >🗑 批量删除（{{ selectedRows.length }}）</el-button>
          <el-button
            v-else
            type="success"
            :disabled="selectedRows.length === 0"
            @click="batchRestore"
          >♻ 批量恢复（{{ selectedRows.length }}）</el-button>
        </el-col>
        <el-col :span="6" style="text-align: right">
          <el-button
            :type="trashMode ? 'primary' : 'default'"
            :disabled="!currentCollection"
            @click="toggleTrash"
          >{{ trashMode ? '← 返回论文列表' : `🗑 回收站${deletedTotal ? '（' + deletedTotal + '）' : ''}` }}</el-button>
          <el-tag v-if="trashMode" type="danger" style="margin-left:8px">已删除 {{ total }} 条</el-tag>
          <el-tag v-else-if="searchMode" type="success" style="margin-left:8px">搜索结果 {{ total }} 条</el-tag>
          <el-tag v-else-if="currentCollection" type="info" style="margin-left:8px">共 {{ total }} 条（未关联的排前面）</el-tag>
        </el-col>
      </el-row>
    </el-card>

    <!-- 数据表格 -->
    <el-card class="table-card" v-if="currentCollection">
      <el-table
        ref="tableRef"
        :data="papers"
        border
        stripe
        v-loading="loading"
        style="width: 100%"
        @selection-change="rows => selectedRows = rows"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column v-if="showCollectionCol" label="所属分类" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ collectionLabel(row.collection) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="论文标题" min-width="260" show-overflow-tooltip />
        <el-table-column prop="authors" label="作者" width="140" show-overflow-tooltip />
        <el-table-column prop="journalName" label="期刊名称" width="160" show-overflow-tooltip />
        <el-table-column prop="publishDate" label="发表日期" width="130" />
        <el-table-column label="关联数据库" min-width="180">
          <template #default="{ row }">
            <el-tag v-if="row.reAnnotate" type="danger" size="small" effect="dark" class="db-tag">🔄 待重新标注</el-tag>
            <template v-if="row.relateDBs">
              <el-tag
                v-for="name in relateNames(row.relateDBs)"
                :key="name"
                type="success"
                size="small"
                class="db-tag"
              >{{ name }}</el-tag>
            </template>
            <el-tag v-else-if="row.noRelateDbs" type="info" size="small" class="db-tag">无关联</el-tag>
            <el-tag v-else-if="!row.reAnnotate" type="warning" size="small" class="db-tag">未关联</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传状态" width="130">
          <template #default="{ row }">
            <el-tooltip
              v-if="row.uploaded"
              :content="`最近上传：${row.uploadedAt || '未记录'}`"
              placement="top"
            >
              <el-tag
                :type="row.uploadDirty ? 'warning' : 'success'"
                size="small"
                effect="dark"
              >{{ row.uploadDirty ? '已上传·待更新' : '已上传' }}</el-tag>
            </el-tooltip>
            <el-tag v-else-if="isAnnotated(row)" type="primary" size="small" effect="plain">待上传</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="trashMode" label="删除时间" width="160">
          <template #default="{ row }">
            <span class="muted">{{ row.deletedAt || '未记录' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="trashMode ? 180 : 330" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="showDetail(row)">详情</el-button>
            <template v-if="trashMode">
              <el-button type="success" link @click="singleRestore(row)">恢复</el-button>
            </template>
            <template v-else>
              <el-button type="warning" link @click="openRelateEdit(row)">关联</el-button>
              <el-button type="danger" link @click="openPaperEdit(row)">编辑</el-button>
              <el-button
                v-if="row.uploaded && !row.reAnnotate"
                type="warning"
                link
                @click="singleReAnnotate(row)"
              >打回标注</el-button>
              <el-button type="danger" link @click="singleDelete(row)">删除</el-button>
            </template>
            <el-button v-if="row.sourceUrl" type="success" link @click="openLink(row.sourceUrl)">原文</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination" v-if="!searchMode">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="loadPapers"
          @current-change="loadPapers"
        />
      </div>
    </el-card>

    <!-- 空状态 -->
    <el-empty v-else description="请先选择一个集合" />

    <!-- 高级搜索对话框 -->
    <el-dialog v-model="searchVisible" title="🔍 高级搜索（填写的条件同时满足）" width="640px">
      <el-form :model="cond" label-width="90px">
        <el-row :gutter="12">
          <el-col :span="24">
            <el-form-item label="搜索范围">
              <el-radio-group v-model="searchScope" :disabled="currentCollection === 'all'">
                <el-radio-button value="current">
                  {{ currentCollection === 'all' ? '全部分类' : `当前分类（${collectionLabel(currentCollection)}）` }}
                </el-radio-button>
                <el-radio-button value="all">全部分类</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="标题"><el-input v-model="cond.title" clearable /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="作者"><el-input v-model="cond.authors" clearable /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="摘要内容"><el-input v-model="cond.abstractText" clearable /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="关键词"><el-input v-model="cond.keywords" clearable /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="期刊名称"><el-input v-model="cond.journalName" clearable /></el-form-item></el-col>
          <el-col :span="24">
            <el-form-item label="发表日期">
              <el-date-picker
                v-model="publishDateRange"
                type="monthrange"
                value-format="YYYY-MM"
                range-separator="至"
                start-placeholder="开始月份"
                end-placeholder="结束月份"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="转换日期">
              <el-date-picker
                v-model="releaseDateRange"
                type="daterange"
                value-format="YYYY-MM-DD"
                range-separator="至"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="关联状态">
              <el-radio-group v-model="cond.relateStatus">
                <el-radio-button value="all">全部</el-radio-button>
                <el-radio-button value="unrelated">未关联</el-radio-button>
                <el-radio-button value="related">已关联</el-radio-button>
                <el-radio-button value="none">已确认无关联</el-radio-button>
                <el-radio-button value="reannotate">待重新标注</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="上传状态">
              <el-radio-group v-model="cond.uploadStatus">
                <el-radio-button value="all">全部</el-radio-button>
                <el-radio-button value="notUploaded">未上传</el-radio-button>
                <el-radio-button value="uploaded">已上传</el-radio-button>
                <el-radio-button value="dirty">已上传·待更新</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="searchVisible = false">取消</el-button>
        <el-button @click="clearCond">清空条件</el-button>
        <el-button type="primary" :loading="loading" @click="doAdvancedSearch">搜索</el-button>
      </template>
    </el-dialog>

    <!-- 关联数据库编辑对话框 -->
    <el-dialog v-model="relateVisible" width="620px">
      <template #header>
        <b>编辑关联数据库</b>
        <div class="relate-dialog-title" v-if="relatePaper">{{ relatePaper.title }}</div>
      </template>
      <DbSelector v-model="relateIds" max-height="42vh" />
      <template #footer>
        <el-popconfirm title="确认这篇论文不关联任何数据库？" @confirm="saveRelate(true)">
          <template #reference>
            <el-button type="info" plain>确认无关联</el-button>
          </template>
        </el-popconfirm>
        <el-button @click="relateVisible = false">取消</el-button>
        <el-button type="primary" :disabled="relateIds.length === 0" :loading="saving" @click="saveRelate(false)">保存</el-button>
      </template>
    </el-dialog>

    <!-- 论文编辑对话框：左改字段，右实时预览 -->
    <el-dialog v-model="editVisible" width="1200px" top="4vh" :close-on-click-modal="false">
      <template #header>
        <b>编辑论文</b>
        <span class="edit-dialog-tip">修改字段后，右侧实时预览最终展示效果（Content HTML 会随字段自动重新生成）</span>
      </template>
      <div class="edit-area" v-if="editForm">
        <!-- 左：字段表单 -->
        <el-scrollbar class="edit-form" max-height="66vh">
          <el-form :model="editForm" label-width="80px" label-position="top">
            <el-form-item label="标题" required>
              <el-input v-model="editForm.title" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }" />
            </el-form-item>
            <el-form-item label="作者（多个用分号隔开）">
              <el-input v-model="editForm.authors" />
            </el-form-item>
            <el-row :gutter="10">
              <el-col :span="12">
                <el-form-item label="期刊名称"><el-input v-model="editForm.journalName" /></el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="发表日期"><el-input v-model="editForm.publishDate" /></el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="摘要">
              <el-input v-model="editForm.abstractText" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" />
            </el-form-item>
            <el-form-item label="关键词（分号隔开）">
              <el-input v-model="editForm.keywords" />
            </el-form-item>
            <el-form-item label="原文链接">
              <el-input v-model="editForm.sourceUrl" />
            </el-form-item>
            <el-row :gutter="10" v-if="editForm.collection !== 'domestic_papers'">
              <el-col :span="12">
                <el-form-item label="学科分类">
                  <el-select v-model="editForm.type" style="width:100%">
                    <el-option label="Accounting" value="Accounting" />
                    <el-option label="Finance" value="Finance" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="期刊缩写"><el-input v-model="editForm.journalAbbName" /></el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="10">
              <el-col :span="12">
                <el-form-item label="转换日期"><el-input v-model="editForm.releaseDate" placeholder="yyyy-MM-dd" /></el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item>
                  <template #label>PaperID <el-text size="small" type="warning">（去重标识，谨慎修改）</el-text></template>
                  <el-input v-model="editForm.paperId" />
                </el-form-item>
              </el-col>
            </el-row>
          </el-form>
        </el-scrollbar>

        <!-- 右：实时预览 -->
        <div class="edit-preview">
          <div class="preview-title">
            展示效果预览
            <el-tag v-if="previewLoading" size="small" type="info">生成中…</el-tag>
          </div>
          <el-scrollbar max-height="62vh">
            <div class="html-preview" v-html="previewHtml"></div>
          </el-scrollbar>
        </div>
      </div>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!editForm?.title?.trim()" @click="savePaperEdit">保存修改</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="dialogVisible" title="论文详情" width="850px">
      <template v-if="currentPaper">
        <el-tabs v-model="detailTab">
          <el-tab-pane label="结构化数据" name="data">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="标题" :span="2">
                <strong>{{ currentPaper.title }}</strong>
              </el-descriptions-item>
              <el-descriptions-item label="作者">{{ currentPaper.authors }}</el-descriptions-item>
              <el-descriptions-item label="期刊">{{ currentPaper.journalName }}</el-descriptions-item>
              <el-descriptions-item label="发表日期">{{ currentPaper.publishDate }}</el-descriptions-item>
              <el-descriptions-item label="发布日期">{{ currentPaper.releaseDate }}</el-descriptions-item>
              <el-descriptions-item label="PaperID">{{ currentPaper.paperId }}</el-descriptions-item>
              <el-descriptions-item label="期刊缩写">{{ currentPaper.journalAbbName }}</el-descriptions-item>
              <el-descriptions-item label="分类">{{ currentPaper.type }}</el-descriptions-item>
              <el-descriptions-item label="来源文件">{{ currentPaper.sourceFile }}</el-descriptions-item>
              <el-descriptions-item label="关联数据库" :span="2">
                <template v-if="currentPaper.relateDBs">
                  <el-tag
                    v-for="name in relateNames(currentPaper.relateDBs)"
                    :key="name"
                    type="success"
                    size="small"
                    style="margin: 2px"
                  >{{ name }}</el-tag>
                </template>
                <el-tag v-else-if="currentPaper.noRelateDbs" type="info" size="small">无关联</el-tag>
                <el-tag v-else type="warning" size="small">未关联</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="上传状态" :span="2">
                <template v-if="currentPaper.uploaded">
                  <el-tag :type="currentPaper.uploadDirty ? 'warning' : 'success'" size="small">
                    {{ currentPaper.uploadDirty ? '已上传·待更新' : '已上传' }}
                  </el-tag>
                  <span class="muted" style="margin-left: 8px; font-size: 12px;">
                    最近上传：{{ currentPaper.uploadedAt || '未记录' }}
                  </span>
                </template>
                <el-tag v-else-if="isAnnotated(currentPaper)" type="primary" size="small" effect="plain">待上传</el-tag>
                <el-tag v-else type="info" size="small">未标注，暂不上传</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="关键词" :span="2">
                <el-tag
                  v-for="kw in splitKeywords(currentPaper.keywords)"
                  :key="kw"
                  size="small"
                  style="margin: 2px"
                >{{ kw }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="原文链接" :span="2">
                <a v-if="currentPaper.sourceUrl" :href="currentPaper.sourceUrl" target="_blank">{{ currentPaper.sourceUrl }}</a>
              </el-descriptions-item>
              <el-descriptions-item label="摘要" :span="2">
                <div style="max-height: 200px; overflow-y: auto; line-height: 1.8; color: #555;">
                  {{ currentPaper.abstractText }}
                </div>
              </el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>
          <el-tab-pane label="HTML 原文预览" name="preview" v-if="currentPaper.content">
            <div class="html-preview" v-html="currentPaper.content"></div>
          </el-tab-pane>
          <el-tab-pane label="HTML 源码" name="source" v-if="currentPaper.content">
            <pre class="html-source">{{ currentPaper.content }}</pre>
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPapers, getCollections, searchAdvancedPapers, reAnnotatePapers, saveAnnotation, previewPaper, updatePaper,
  deletePapers, restorePapers, getDeletedPapers } from '../api/paper'
import { useDataHubDb } from '../composables/useDataHubDb'
import DbSelector from '../components/DbSelector.vue'

const { load: loadDbNames, relateNames } = useDataHubDb()

// 集合名 -> 中文分类名
const COLLECTION_LABELS = {
  all: '全部论文',
  domestic_papers: '国内论文',
  intl_chinese_accounting: '国际中文-会计',
  intl_chinese_finance: '国际中文-金融',
  intl_accounting: '国际-会计',
  intl_finance: '国际-金融'
}
const collectionLabel = name => COLLECTION_LABELS[name] || name

const papers = ref([])
const total = ref(0)
const loading = ref(false)
const collections = ref([])
const currentCollection = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const selectedRows = ref([])
const tableRef = ref()

// 回收站模式：列表切成"已删除的论文"，操作列换成恢复
const trashMode = ref(false)

const totalAllCount = computed(() =>
  collections.value.reduce((n, c) => n + c.count, 0)
)

const deletedTotal = computed(() =>
  collections.value.reduce((n, c) => n + (c.deletedCount || 0), 0)
)

// 数据跨集合时（选了"全部论文"或用"全部分类"搜过），展示"所属分类"列
const searchedAll = ref(false)
const showCollectionCol = computed(() =>
  currentCollection.value === 'all' || (searchMode.value && searchedAll.value)
)

// 高级搜索
const searchVisible = ref(false)
const searchMode = ref(false)
const searchScope = ref('current')   // current=当前分类, all=全部分类
const releaseDateRange = ref(null)
const publishDateRange = ref(null)
const emptyCond = () => ({
  title: '', authors: '', abstractText: '', keywords: '',
  journalName: '', relateStatus: 'all', uploadStatus: 'all'
})
const cond = ref(emptyCond())

// 关联编辑
const relateVisible = ref(false)
const relatePaper = ref(null)
const relateIds = ref([])
const saving = ref(false)

const dialogVisible = ref(false)
const currentPaper = ref(null)
const detailTab = ref('data')

function splitKeywords(kw) {
  if (!kw) return []
  return kw.split(/[;；,，]/).filter(Boolean).slice(0, 10)
}

/** 已处理过关联 = 有关联数据库，或已人工确认无关联 */
function isAnnotated(row) {
  return !!row.relateDBs || row.noRelateDbs === true
}

async function loadCollections() {
  try {
    const res = await getCollections()
    collections.value = res.data
  } catch (e) {
    console.error('加载集合列表失败', e)
  }
}

function onCollectionChange() {
  currentPage.value = 1
  resetSearch()
}

async function loadPapers() {
  if (!currentCollection.value) return
  loading.value = true
  try {
    const fetch = trashMode.value ? getDeletedPapers : getPapers
    const res = await fetch(currentCollection.value, currentPage.value - 1, pageSize.value)
    papers.value = res.data.data
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

// ==================== 高级搜索 ====================

async function doAdvancedSearch() {
  loading.value = true
  try {
    const useAll = searchScope.value === 'all' || currentCollection.value === 'all'
    const body = {
      collection: useAll ? 'all' : currentCollection.value,
      ...cond.value,
      publishDateFrom: publishDateRange.value?.[0] || '',
      publishDateTo: publishDateRange.value?.[1] || '',
      releaseDateFrom: releaseDateRange.value?.[0] || '',
      releaseDateTo: releaseDateRange.value?.[1] || ''
    }
    const res = await searchAdvancedPapers(body)
    papers.value = res.data.data
    total.value = res.data.total
    searchMode.value = true
    searchedAll.value = useAll
    searchVisible.value = false
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '搜索失败')
  } finally {
    loading.value = false
  }
}

function clearCond() {
  cond.value = emptyCond()
  releaseDateRange.value = null
  publishDateRange.value = null
}

function resetSearch() {
  searchMode.value = false
  searchedAll.value = false
  searchScope.value = 'current'
  clearCond()
  loadPapers()
}

// ==================== 批量送回标注 ====================

async function batchReAnnotate() {
  const rows = selectedRows.value
  try {
    await ElMessageBox.confirm(
      `确定把选中的 ${rows.length} 篇论文送回「数据库标注」重新处理吗？原有关联会保留，可在标注页修改。`,
      '批量送回标注',
      { type: 'warning' }
    )
  } catch { return }

  try {
    // 跨集合场景下按论文所属集合分组，逐集合调用
    const byCollection = {}
    rows.forEach(r => {
      const col = r.collection || currentCollection.value
      ;(byCollection[col] = byCollection[col] || []).push(r.id)
    })
    await Promise.all(
      Object.entries(byCollection).map(([col, ids]) => reAnnotatePapers(col, ids))
    )
    ElMessage.success(`已送回 ${rows.length} 篇，去「数据库标注」页面处理`)
    tableRef.value?.clearSelection()
    searchMode.value ? refreshSearchRows(rows) : loadPapers()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

/** 搜索结果模式下就地更新行状态，不打断用户的搜索结果 */
function refreshSearchRows(rows) {
  const ids = new Set(rows.map(r => r.id))
  papers.value.forEach(p => { if (ids.has(p.id)) p.reAnnotate = true })
}

/** 单篇打回标注：已上传的论文想改关联数据库时用 */
async function singleReAnnotate(row) {
  try {
    await ElMessageBox.confirm(
      '这篇论文已经上传到外网，打回后可在「数据库标注」页修改关联数据库；' +
      '改完下次上传会把最新结果推送过去。原有关联会保留并预勾选。',
      '打回数据库标注',
      { type: 'warning', confirmButtonText: '打回标注', cancelButtonText: '取消' }
    )
  } catch { return }

  try {
    const col = row.collection || currentCollection.value
    await reAnnotatePapers(col, [row.id])
    row.reAnnotate = true
    ElMessage.success('已打回，去「数据库标注」页面处理')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

// ==================== 删除 / 恢复 ====================

/** 按论文所属集合分组调接口（"全部论文"视图下一批可能跨多个集合） */
function groupByCollection(rows) {
  const byCollection = {}
  rows.forEach(r => {
    const col = r.collection || currentCollection.value
    ;(byCollection[col] = byCollection[col] || []).push(r.id)
  })
  return byCollection
}

function toggleTrash() {
  trashMode.value = !trashMode.value
  searchMode.value = false
  searchedAll.value = false
  clearCond()
  currentPage.value = 1
  tableRef.value?.clearSelection()
  loadPapers()
}

/** 已上传过外网的论文删掉后，150 上还留着，得提示用户去重新上传才会同步 */
function deleteHint(rows) {
  const uploaded = rows.filter(r => r.uploaded).length
  return uploaded > 0
    ? `其中 ${uploaded} 篇已上传到外网，150 上的数据要等下次点「上传外网」才会同步删掉。`
    : ''
}

async function batchDelete() {
  const rows = selectedRows.value
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${rows.length} 篇论文吗？删除后会从论文列表和标注待办中消失，` +
      `可以在回收站恢复。${deleteHint(rows)}`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }

  try {
    const results = await Promise.all(
      Object.entries(groupByCollection(rows)).map(([col, ids]) => deletePapers(col, ids))
    )
    const uploadedAffected = results.reduce((n, r) => n + (r.data.uploadedAffected || 0), 0)
    ElMessage.success(
      `已删除 ${rows.length} 篇` +
      (uploadedAffected > 0 ? `，其中 ${uploadedAffected} 篇需重新上传外网才会从 150 移除` : '')
    )
    tableRef.value?.clearSelection()
    await afterMutate()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除失败')
  }
}

async function singleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除《${row.title}》吗？可以在回收站恢复。${deleteHint([row])}`,
      '删除论文',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }

  try {
    const col = row.collection || currentCollection.value
    const { data } = await deletePapers(col, [row.id])
    ElMessage.success(
      data.uploadedAffected > 0
        ? '已删除，该论文需重新上传外网才会从 150 移除'
        : '已删除，可在回收站恢复'
    )
    await afterMutate()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除失败')
  }
}

async function batchRestore() {
  const rows = selectedRows.value
  try {
    await ElMessageBox.confirm(
      `确定恢复选中的 ${rows.length} 篇论文吗？恢复后会重新回到论文列表。`,
      '批量恢复',
      { type: 'info', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch { return }

  try {
    await Promise.all(
      Object.entries(groupByCollection(rows)).map(([col, ids]) => restorePapers(col, ids))
    )
    ElMessage.success(`已恢复 ${rows.length} 篇`)
    tableRef.value?.clearSelection()
    await afterMutate()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '恢复失败')
  }
}

async function singleRestore(row) {
  try {
    const col = row.collection || currentCollection.value
    await restorePapers(col, [row.id])
    ElMessage.success('已恢复')
    await afterMutate()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '恢复失败')
  }
}

/** 删/恢复之后：集合条数变了要重拉，当前页也要刷新 */
async function afterMutate() {
  await loadCollections()
  if (searchMode.value) {
    await doAdvancedSearch()
    return
  }
  await loadPapers()
  // 把当前页删空了就退回上一页，避免停在空白页
  if (papers.value.length === 0 && currentPage.value > 1) {
    currentPage.value--
    await loadPapers()
  }
}

// ==================== 单条关联编辑 ====================

function openRelateEdit(row) {
  relatePaper.value = row
  relateIds.value = row.relateDBs
    ? row.relateDBs.split(',').map(s => s.trim()).filter(Boolean)
    : []
  relateVisible.value = true
}

async function saveRelate(noRelateDbs) {
  saving.value = true
  try {
    const relateDBs = noRelateDbs ? '' : relateIds.value.join(',')
    const col = relatePaper.value.collection || currentCollection.value
    const { data } = await saveAnnotation(col, relatePaper.value.id, relateDBs, noRelateDbs)
    // 就地更新行数据，避免整页刷新丢失位置
    relatePaper.value.relateDBs = relateDBs
    relatePaper.value.noRelateDbs = noRelateDbs
    relatePaper.value.reAnnotate = false
    relatePaper.value.uploadDirty = data.uploadDirty === true
    ElMessage.success(data.uploadDirty
      ? '已保存，该论文已标记为「待更新」，下次上传外网会推送最新关联'
      : '已保存')
    relateVisible.value = false
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function showDetail(paper) {
  currentPaper.value = paper
  dialogVisible.value = true
}

// ==================== 论文编辑（实时预览） ====================

const editVisible = ref(false)
const editForm = ref(null)
const editRow = ref(null)          // 正在编辑的表格行引用
const previewHtml = ref('')
const previewLoading = ref(false)
let previewTimer = null

const EDIT_FIELDS = ['title', 'authors', 'journalName', 'publishDate', 'releaseDate',
  'keywords', 'abstractText', 'sourceUrl', 'paperId', 'type', 'journalAbbName']

function openPaperEdit(row) {
  editRow.value = row
  const form = { id: row.id, collection: row.collection || currentCollection.value }
  EDIT_FIELDS.forEach(f => { form[f] = row[f] || '' })
  editForm.value = form
  previewHtml.value = row.content || ''
  editVisible.value = true
}

// 字段一变就（防抖 400ms）调后端重新生成 Content 预览
watch(editForm, () => {
  if (!editVisible.value || !editForm.value) return
  clearTimeout(previewTimer)
  previewTimer = setTimeout(refreshPreview, 400)
}, { deep: true })

async function refreshPreview() {
  if (!editForm.value) return
  previewLoading.value = true
  try {
    const { data } = await previewPaper(editForm.value)
    previewHtml.value = data.content
  } catch (e) {
    // 预览失败不打断编辑
  } finally {
    previewLoading.value = false
  }
}

async function savePaperEdit() {
  saving.value = true
  try {
    const { data } = await updatePaper(editForm.value)
    // 就地更新表格行（含重新生成的 content），保持列表位置
    EDIT_FIELDS.forEach(f => { editRow.value[f] = editForm.value[f]?.trim?.() ?? editForm.value[f] })
    editRow.value.content = data.content
    ElMessage.success('已保存，Content 已重新生成')
    editVisible.value = false
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function openLink(url) {
  window.open(url, '_blank')
}

onMounted(async () => {
  await loadCollections()
  loadDbNames()
  // 默认展示全部论文
  currentCollection.value = 'all'
  loadPapers()
})
</script>

<style scoped>
.paper-list h2 { margin-bottom: 20px; }
.search-card { margin-bottom: 20px; }
.muted { color: #c0c4cc; }
.pagination { margin-top: 20px; display: flex; justify-content: flex-end; }
.db-tag { margin: 2px 4px 2px 0; }
.relate-dialog-title {
  font-size: 13px;
  font-weight: normal;
  color: #909399;
  margin-top: 4px;
}
.edit-dialog-tip {
  font-size: 12px;
  font-weight: normal;
  color: #909399;
  margin-left: 12px;
}
.edit-area {
  display: flex;
  gap: 16px;
}
.edit-form {
  flex: 1;
  min-width: 0;
  padding-right: 8px;
}
.edit-preview {
  flex: 1;
  min-width: 0;
  border-left: 1px solid #ebeef5;
  padding-left: 16px;
}
.preview-title {
  font-weight: bold;
  color: #409eff;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.html-preview {
  max-height: 500px;
  overflow-y: auto;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  line-height: 1.8;
}
.html-source {
  max-height: 500px;
  overflow: auto;
  padding: 12px;
  background: #f5f5f5;
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
