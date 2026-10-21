<template>
  <div class="import-page">
    <h2>📥 Excel 批量导入论文</h2>
    <p class="subtitle">支持单文件上传 + 服务器本地目录批量导入，自动解析 HTML Content 提取作者/摘要/关键词</p>

    <!-- 方式一：单文件上传 -->
    <el-card class="upload-card">
      <template #header><span>📄 方式一：上传单个 Excel 文件</span></template>
      <el-upload
        ref="uploadRef"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".xlsx,.xls"
        :on-change="handleFileChange"
        :on-remove="handleFileRemove"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">将 Excel 文件拖到此处，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="upload-tip">
            文件名自动映射到同名集合：DomesticPaper、Internationalpaper_Accounting 等
          </div>
        </template>
      </el-upload>

      <div class="actions">
        <el-button
          type="primary"
          size="large"
          :loading="importing"
          :disabled="!selectedFile"
          @click="doSingleImport"
        >
          <el-icon><Upload /></el-icon>
          {{ importing ? '正在导入...' : '上传并导入' }}
        </el-button>
      </div>
    </el-card>

    <!-- 方式二：目录批量导入 -->
    <el-card class="upload-card" style="margin-top: 20px;">
      <template #header><span>📁 方式二：服务器目录批量导入</span></template>
      <p style="color: #909399; margin-bottom: 12px;">
        输入服务器上 Excel 文件所在目录路径，自动扫描所有 .xlsx/.xls 文件并导入
      </p>
      <el-input
        v-model="dirPath"
        placeholder="例如：./内网导入/导入内网"
        clearable
        style="margin-bottom: 12px;"
      >
        <template #prepend>目录路径</template>
      </el-input>
      <div class="actions">
        <el-button
          type="success"
          size="large"
          :loading="batchImporting"
          :disabled="!dirPath"
          @click="doBatchImport"
        >
          <el-icon><FolderOpened /></el-icon>
          {{ batchImporting ? '正在批量导入...' : '批量导入目录' }}
        </el-button>
      </div>
    </el-card>

    <!-- 导入结果 -->
    <el-card v-if="result" class="result-card">
      <template #header><span>📊 导入结果</span></template>

      <!-- 单文件结果 -->
      <template v-if="result.file">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="文件名">{{ result.file }}</el-descriptions-item>
          <el-descriptions-item label="目标集合">{{ result.collection }}</el-descriptions-item>
          <el-descriptions-item label="成功导入">{{ result.inserted }} 条</el-descriptions-item>
        </el-descriptions>

        <div v-if="result.skipped?.length" class="skipped-list">
          <h4>跳过的记录：</h4>
          <el-tag v-for="(s, i) in result.skipped" :key="i" type="info" style="margin: 4px">{{ s }}</el-tag>
        </div>
      </template>

      <!-- 批量结果 -->
      <template v-if="result.totalInserted !== undefined">
        <el-statistic title="总共导入" :value="result.totalInserted">
          <template #suffix>条</template>
        </el-statistic>
        <div v-if="result.results?.length" style="margin-top: 16px;">
          <el-table :data="result.results" border stripe size="small">
            <el-table-column prop="file" label="文件" width="300" />
            <el-table-column prop="collection" label="集合" width="200" />
            <el-table-column prop="inserted" label="导入数" width="100" />
            <el-table-column prop="skipped" label="跳过" width="80" />
            <el-table-column label="错误">
              <template #default="{ row }">
                <el-tag v-if="row.errors?.length" type="danger" size="small">{{ row.errors.length }} 个错误</el-tag>
                <el-tag v-else type="success" size="small">无</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>

      <!-- 错误 -->
      <div v-if="result.errors?.length" class="error-list">
        <el-alert
          v-for="(err, i) in result.errors"
          :key="i"
          :title="err"
          type="error"
          show-icon
          :closable="false"
          style="margin-top: 8px"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { importPapers, importBatch } from '../api/paper'

const uploadRef = ref(null)
const selectedFile = ref(null)
const importing = ref(false)
const batchImporting = ref(false)
const dirPath = ref('./内网导入/导入内网')
const result = ref(null)

function handleFileChange(file) {
  selectedFile.value = file.raw
}

function handleFileRemove() {
  selectedFile.value = null
  result.value = null
}

async function doSingleImport() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择 Excel 文件')
    return
  }
  try {
    await ElMessageBox.confirm(
      `即将导入「${selectedFile.value.name}」（${(selectedFile.value.size / 1024).toFixed(1)} KB），系统会自动识别类型并去重。确定继续？`,
      '确认导入',
      { confirmButtonText: '确定导入', cancelButtonText: '取消', type: 'info' }
    )
  } catch { return }  // 用户取消

  importing.value = true
  result.value = null
  try {
    const res = await importPapers(selectedFile.value)
    result.value = res.data
    const skipped = res.data.skipped?.length || 0
    const msg = skipped > 0
      ? `导入完成：新增 ${res.data.inserted} 条，跳过重复 ${skipped} 条 → ${res.data.collection}`
      : `导入完成：${res.data.inserted} 条 → ${res.data.collection}`
    ElMessage.success(msg)
  } catch (e) {
    ElMessage.error('导入失败: ' + (e.response?.data?.message || e.message))
  } finally {
    importing.value = false
  }
}

async function doBatchImport() {
  if (!dirPath.value.trim()) {
    ElMessage.warning('请输入目录路径')
    return
  }
  try {
    await ElMessageBox.confirm(
      `即将扫描「${dirPath.value}」下所有 Excel 文件并批量导入，系统会自动去重。确定继续？`,
      '确认批量导入',
      { confirmButtonText: '确定导入', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }

  batchImporting.value = true
  result.value = null
  try {
    const res = await importBatch(dirPath.value.trim())
    result.value = res.data
    const skipped = res.data.results?.reduce((sum, r) => sum + (r.skipped || 0), 0) || 0
    const msg = skipped > 0
      ? `批量导入完成：新增 ${res.data.totalInserted} 条，跳过重复 ${skipped} 条`
      : `批量导入完成：共 ${res.data.totalInserted} 条`
    ElMessage.success(msg)
  } catch (e) {
    ElMessage.error('批量导入失败: ' + (e.response?.data?.message || e.message))
  } finally {
    batchImporting.value = false
  }
}
</script>

<style scoped>
.import-page h2 { margin-bottom: 4px; }
.subtitle { color: #909399; margin-bottom: 20px; }
.upload-icon { font-size: 48px; color: #409EFF; }
.upload-text { color: #606266; font-size: 15px; margin-top: 12px; }
.upload-tip { color: #909399; font-size: 13px; margin-top: 8px; }
.actions { text-align: center; margin-top: 16px; }
.result-card { margin-top: 20px; }
.error-list, .skipped-list { margin-top: 16px; }
.skipped-list h4 { margin-bottom: 8px; color: #909399; }
</style>
