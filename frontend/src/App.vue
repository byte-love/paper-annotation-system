<template>
  <div id="app-container">
    <el-container>
      <!-- 侧边栏 -->
      <el-aside width="220px">
        <div class="logo">📄 期刊论文管理</div>
        <el-menu
          :default-active="currentRoute"
          router
          background-color="#304156"
          text-color="#bfcbd9"
          active-text-color="#409EFF"
        >
          <el-menu-item index="/import">
            <el-icon><Upload /></el-icon>
            <span>Excel 导入</span>
          </el-menu-item>
          <el-menu-item index="/papers">
            <el-icon><Document /></el-icon>
            <span>论文列表</span>
          </el-menu-item>
          <el-menu-item index="/annotation">
            <el-icon><Link /></el-icon>
            <span>数据库标注</span>
          </el-menu-item>
          <el-menu-item index="/export-preview">
            <el-icon><Download /></el-icon>
            <span>导出 Excel</span>
          </el-menu-item>
          <el-menu-item index="/upload-external">
            <el-icon><UploadFilled /></el-icon>
            <span>上传外网</span>
          </el-menu-item>
          <el-menu-item index="/changelog">
            <el-icon><Memo /></el-icon>
            <span>更新日志</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容 -->
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const currentRoute = computed(() => route.path)
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }

#app-container {
  height: 100vh;
  font-family: 'Microsoft YaHei', 'PingFang SC', sans-serif;
}

.el-aside {
  background-color: #304156;
  min-height: 100vh;
  overflow-x: hidden;
}

.logo {
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  text-align: center;
  padding: 20px 0;
  border-bottom: 1px solid #4a5b6e;
  margin-bottom: 8px;
}

.el-menu {
  border-right: none !important;
}

.el-main {
  background: #f0f2f5;
  padding: 24px;
}
</style>
