import { ref } from 'vue'
import { getDataHubDatabases } from '../api/paper'

// DataHub 数据库目录的全局缓存（230 条基本不变，整个应用只请求一次）
const groups = ref([])        // [{partition, databases:[{id, name}]}]
const nameMap = ref({})       // id -> 中文名
let loaded = false

export function useDataHubDb() {
  async function load() {
    if (loaded) return
    const { data } = await getDataHubDatabases()
    groups.value = data
    const m = {}
    data.forEach(g => g.databases.forEach(db => { m[db.id] = db.name }))
    nameMap.value = m
    loaded = true
  }

  /** id -> 中文名（找不到时原样返回 id） */
  const dbName = id => nameMap.value[id] || id

  /** "CPTD,DARD" -> ["专利文本", "数据资产"] */
  const relateNames = relateDBs =>
    (relateDBs || '').split(',').map(s => s.trim()).filter(Boolean).map(dbName)

  return { groups, nameMap, load, dbName, relateNames }
}
