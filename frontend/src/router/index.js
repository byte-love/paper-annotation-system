import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/import'
  },
  {
    path: '/import',
    name: 'Import',
    component: () => import('../views/PaperImport.vue')
  },
  {
    path: '/papers',
    name: 'PaperList',
    component: () => import('../views/PaperList.vue')
  },
  {
    path: '/annotation',
    name: 'Annotation',
    component: () => import('../views/Annotation.vue')
  },
  {
    path: '/export-preview',
    name: 'ExportPreview',
    component: () => import('../views/ExportPreview.vue')
  },
  {
    path: '/upload-external',
    name: 'UploadExternal',
    component: () => import('../views/UploadToExternal.vue')
  },
  {
    path: '/changelog',
    name: 'Changelog',
    component: () => import('../views/Changelog.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
