import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 120000   // 批量导入大文件需要更长时间
})

/**
 * 上传单个 Excel 文件导入
 */
export function importPapers(file, collection = null) {
  const formData = new FormData()
  formData.append('file', file)
  if (collection) formData.append('collection', collection)
  return api.post('/papers/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 批量导入：指定本地目录路径
 */
export function importBatch(dirPath) {
  return api.post('/papers/import/batch', { dirPath })
}

/**
 * 获取所有集合列表（含文档数）
 */
export function getCollections() {
  return api.get('/papers/collections')
}

/**
 * 分页查询指定集合的论文
 */
export function getPapers(collection, page = 0, size = 20) {
  return api.get('/papers', { params: { collection, page, size } })
}

/**
 * 搜索论文
 */
export function searchPapers(collection, keyword) {
  return api.get('/papers/search', { params: { collection, keyword } })
}

// ==================== 数据库标注 ====================

/**
 * 获取 DataHub 数据库目录（按板块分组）
 */
export function getDataHubDatabases() {
  // _t 防止浏览器缓存旧响应（partition 字段同步前的空值曾被缓存过）
  return api.get('/annotation/databases', { params: { _t: Date.now() } })
}

/** 按 id 列表取论文（保持顺序，已标注的也能取到），用于恢复标注批次 */
export function getPapersByIds(collection, ids) {
  return api.post('/annotation/papersByIds', { collection, ids })
}

/**
 * 各集合待标注论文数量
 */
export function getPendingCounts() {
  return api.get('/annotation/counts')
}

/**
 * 查询指定集合的全部待标注论文
 */
export function getPendingPapers(collection) {
  return api.get('/annotation/pending', { params: { collection } })
}

/**
 * 保存单篇论文的标注结果
 * @param {string} collection 集合名
 * @param {string} id 论文 _id
 * @param {string} relateDBs 勾选的数据库 ID，英文逗号分隔，如 "CPTD,DARD"
 * @param {boolean} noRelateDbs 确认该论文无关联数据库
 */
export function saveAnnotation(collection, id, relateDBs, noRelateDbs = false) {
  return api.put('/annotation/save', { collection, id, relateDBs, noRelateDbs })
}

/**
 * 批量送回"数据库标注"重新处理
 */
export function reAnnotatePapers(collection, ids) {
  return api.post('/annotation/reAnnotate', { collection, ids })
}

/**
 * 高级搜索（多字段组合条件，条件之间为"且"）
 * @param {object} cond { collection, title, abstractText, authors, keywords,
 *                        journalName, publishDate, releaseDateFrom, releaseDateTo, relateStatus }
 */
export function searchAdvancedPapers(cond) {
  return api.post('/papers/searchAdvanced', cond)
}

/**
 * 实时预览：根据字段生成 Content HTML（不保存）
 */
export function previewPaper(body) {
  return api.post('/papers/preview', body)
}

/**
 * 保存论文字段修改（后端自动重新生成 Content HTML）
 */
export function updatePaper(body) {
  return api.put('/papers/update', body)
}

// ==================== 删除 / 恢复（软删除）====================

/**
 * 删除论文（软删除，可在回收站恢复）
 * 删除后从论文列表、标注待办、导出、上传范围中消失。
 * 已上传过外网的论文，150 上要等下次「上传外网」才会同步删掉。
 * @param {string} collection 集合名
 * @param {string[]} ids 论文 _id 列表
 */
export function deletePapers(collection, ids) {
  return api.post('/papers/delete', { collection, ids })
}

/**
 * 从回收站恢复论文
 */
export function restorePapers(collection, ids) {
  return api.post('/papers/restore', { collection, ids })
}

/**
 * 回收站：分页查看已删除的论文
 */
export function getDeletedPapers(collection, page = 0, size = 20) {
  return api.get('/papers/deleted', { params: { collection, page, size } })
}

// ==================== 导出预览 ====================

/**
 * 导出预览：按 publishDate 降序 + 有关联数据库的排前面，分页
 */
export function getExportPreview(collection, page = 0, size = 20) {
  return api.get('/papers/export-preview', { params: { collection, page, size } })
}

/**
 * 导出 Excel 文件（返回 blob）
 */
export function exportPapers(collection) {
  return api.get('/papers/export', { params: { collection }, responseType: 'blob' })
}

// ==================== 上传外网 ====================

/**
 * 预览待上传的已标注论文（各小写集合汇总 + 样例）
 */
export function getUploadPreview() {
  return api.get('/upload/preview')
}

/**
 * 执行上传到外网 150
 */
export function executeUpload() {
  return api.post('/upload/execute')
}

/**
 * 回补 v3.0 迁移走的历史数据：归档集合 → 工作集合，标记为已上传
 * @param {boolean} dryRun true=只统计不写入
 */
export function backfillFromArchive(dryRun = true) {
  return api.post('/upload/backfill', null, { params: { dryRun } })
}

export default api
