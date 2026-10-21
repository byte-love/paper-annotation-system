<template>
  <div class="changelog-page">
    <h2>🗒️ 更新日志</h2>

    <el-timeline class="version-timeline">
      <el-timeline-item
        v-for="v in versions"
        :key="v.version"
        :timestamp="`${v.version}　${v.date}`"
        placement="top"
        :type="v.current ? 'primary' : 'info'"
        :hollow="!v.current"
        size="large"
      >
        <el-card shadow="never">
          <div class="version-header">
            <span class="version-title">{{ v.title }}</span>
            <el-tag v-if="v.current" type="success" size="small">当前版本</el-tag>
          </div>
          <div v-for="section in v.sections" :key="section.title" class="section">
            <div class="section-title">{{ section.title }}</div>
            <ul>
              <li v-for="(item, i) in section.items" :key="i" v-html="item"></li>
            </ul>
          </div>
        </el-card>
      </el-timeline-item>
    </el-timeline>
  </div>
</template>

<script setup>
// 版本日志（新版本在前）。发新版时在数组头部加一项即可。
const versions = [
  {
    version: 'v4.1',
    date: '2026-08-05',
    title: '论文删除 + 回收站',
    current: true,
    sections: [
      {
        title: '论文列表支持删除',
        items: [
          '每行操作列新增「<b>删除</b>」，抓取到不符合要求的数据直接删掉',
          '勾选多篇后顶部出现「<b>🗑 批量删除</b>」，一次清掉一批脏数据',
          '删除是<b>软删除</b>：论文从列表、搜索、导出、标注待办中消失，但数据保留，随时可恢复',
          '删除已上传外网的论文时，确认框会提示该论文在 150 上仍然存在，需重新上传才会同步移除'
        ]
      },
      {
        title: '新增回收站',
        items: [
          '论文列表右上角新增「<b>🗑 回收站</b>」，按钮上直接显示已删除的条数',
          '回收站里可查看<b>删除时间</b>，支持单条「恢复」或勾选后「<b>♻ 批量恢复</b>」',
          '恢复后论文原样回到列表，<b>关联数据库、上传状态等字段全部保持不变</b>',
          '点「← 返回论文列表」退出回收站'
        ]
      },
      {
        title: '数据库标注页支持删除',
        items: [
          '标注时发现数据不合要求，左下角「<b>🗑 删除这篇</b>」直接删，<b>自动跳到下一篇</b>，不用退出标注流程',
          '删除后本批进度「已标注 x / y」和页签待办角标同步收敛，不会把删掉的论文继续算在内',
          '删错了去「论文浏览」的回收站恢复'
        ]
      },
      {
        title: '删除与外网 150 的同步',
        items: [
          '推送 150 是<b>全量覆盖</b>，所以删除要等<b>下一次点「上传外网」</b>才会同步到 150',
          '上传预览页新增「<b>🗑 待移除 N 条</b>」标记，明确告知本次推送会从 150 上抹掉哪些论文',
          '<b>修复</b>：以前只删论文、没有新标注时「上传到外网」按钮是禁用的，删除永远推不到 150；现在有待移除的论文也可以上传',
          '删除的论文会拦住<b>归档集合</b>（大写）里的同一篇副本，回补来的历史论文删掉后不会被归档数据重新推上 150，也不会被再次回补拉回列表',
          'Excel 重新导入时删除记录参与判重，删过的论文不会又被导进来（要恢复请去回收站）'
        ]
      }
    ]
  },
  {
    version: 'v4.0',
    date: '2026-07-28',
    title: '上传外网 + 论文上传状态可追溯',
    current: false,
    sections: [
      {
        title: '新增「上传外网」模块',
        items: [
          '左侧导航新增「<b>上传外网</b>」入口，把已标注论文推送到外网 150 的 SQL Server 数据库',
          '上传前先<b>预览</b>本次新增或改动的论文，逐集合列出条数和样例，确认无误再执行',
          '推送方式为<b>全量覆盖</b>：每次把该分类下所有已上传和新标注的论文一起推过去，保证 150 上的数据完整',
          '排序规则：<b>PublishDate 降序</b> → 同期内有关联数据库的排前面 → 按此顺序分配自增 ID（1, 2, 3…）'
        ]
      },
      {
        title: '上传后论文不再消失，状态一目了然',
        items: [
          '论文上传外网后<b>继续留在论文列表</b>里，不会被迁走。这是本版最重要的行为变更',
          '论文列表新增「<b>上传状态</b>」列：<b>已上传</b>（鼠标悬停可看上传时间）、<b>已上传·待更新</b>、<b>待上传</b>',
          '高级搜索新增「<b>上传状态</b>」筛选条件，可单独筛出未上传、已上传或待更新的论文',
          '论文详情弹窗同步显示上传状态和最近一次上传时间'
        ]
      },
      {
        title: '已上传论文支持打回重新标注',
        items: [
          '已上传论文的操作列新增「<b>打回标注</b>」按钮，单篇即可送回「数据库标注」修改关联数据库',
          '也可以在列表里勾选多篇，用「<b>批量送回标注</b>」一次性打回',
          '打回后原有关联<b>会保留并预勾选</b>，在标注页直接改，不用从头选',
          '改完关联的论文自动标记「<b>已上传·待更新</b>」，下次上传会把最新的关联数据库推送到 150',
          '解决的问题：之前论文一上传就从列表消失，事后想更新关联数据库无从下手'
        ]
      },
      {
        title: '历史数据回补',
        items: [
          '「上传外网」页新增<b>历史数据回补</b>工具，把 v3.0 迁移走的论文找回论文列表',
          '回补的论文自动标记为「已上传」，之后就能正常打回标注、修改关联数据库',
          '支持<b>先试算</b>：先看清各分类能回补多少条，确认后再写入',
          '回补<b>幂等</b>，按 PaperID（缺失时用 标题+期刊+发表日期）去重，重复执行不会产生副本；归档数据保持原样不删'
        ]
      },
      {
        title: '150 SQL Server 目标库',
        items: [
          '自动创建 <b>JournalSystem</b> 数据库及五张表：DomesticPaper、InternationalChinesePaper_Accounting、InternationalChinesePaper_Finance、Internationalpaper_Accounting、Internationalpaper_Finance',
          '表字段：<b>ID</b>（排序自增）、<b>PaperTitle</b>、<b>JournalName</b>、<b>PublishDate</b>、<b>ReleaseDate</b>、<b>Content</b>、<b>RelateDBs</b>、<b>PaperID</b>',
          '每次上传前自动<b>备份</b>现有表到 00 后缀表（如 DomesticPaper → DomesticPaper00），防止数据丢失',
          '新增 <b>GET /api/upload/status</b> 接口和 <b>/db-view.html</b> 页面，可直接在浏览器查看 150 各表数据'
        ]
      },
      {
        title: '技术变更',
        items: [
          'SQL Server 驱动从 mssql-jdbc 换成 <b>jTDS</b>，兼容老版本 SQL Server 的 TLS 配置',
          '论文新增 <b>uploaded</b> / <b>uploadedAt</b> / <b>uploadDirty</b> 三个字段记录上传状态',
          '数据库连接<b>外部化</b>：host/port/user/database 走 application.yml，<b>密码从环境变量读取</b>不入代码库，部署时放 .env 即可（见 .env.example）',
          'deploy.sh 启动前自动加载 .env；部署脚本改为服务器本地执行，自动查找旧部署位置、备份旧 jar、平滑重启'
        ]
      }
    ]
  },
  {
    version: 'v3.0',
    date: '2026-07-21',
    title: '导出 Excel 模块 + 数据修正',
    current: false,
    sections: [
      {
        title: '新增「导出 Excel」模块',
        items: [
          '左侧导航新增「<b>导出 Excel</b>」入口，按五种论文分类分别导出',
          '页面顶部选择分类，下方表格即时预览该分类全部数据',
          '排序规则：按 <b>PublishDate 降序</b>优先，同日期内有关联数据库的论文排在前面',
          '点击「导出 Excel」按钮下载 .xlsx 文件',
          '文件名与原始 Excel 对应：<b>DomesticPaper.xlsx</b> / <b>InternationalChinesePaper_Accounting.xlsx</b> / <b>InternationalChinesePaper_Finance.xlsx</b> / <b>Internationalpaper_Accounting.xlsx</b> / <b>Internationalpaper_Finance.xlsx</b>'
        ]
      },
      {
        title: '导出格式',
        items: [
          '<b>国内论文</b>（7 列）：PaperTitle, JournalName, PublishDate, ReleaseDate, Content, RelateDBs, PaperID',
          '<b>国际论文</b>（9 列）：上面 7 列 + JournalAbnName（期刊缩写）、Type（学科分类）',
          '表头全部英文，列宽统一，长字段自动截断，点单元格可查看全文',
          'Sheet 标签名与文件名一致（如 Internationalpaper_Finance），不再显示"论文导出"'
        ]
      },
      {
        title: 'PublishDate 数据修正',
        items: [
          '修复导入逻辑：<b>「年月」列优先</b>于「期刊」列映射到 PublishDate（原来反了，导致国际论文存入了期刊名格式）',
          '存量 582 篇论文 PublishDate 已标准化为 <b>YYYY年MM月</b> 统一格式（如 Volume 117, December 2026 → 2026年12月）',
          '论文列表和导出 Excel 的 PublishDate 全部使用标准年月格式'
        ]
      }
    ]
  },
  {
    version: 'v2.1',
    date: '2026-07-17',
    title: '数据库标注体验优化',
    current: false,
    sections: [
      {
        title: '标注操作简化',
        items: [
          '操作按钮由四个精简为两个：<b>上一篇</b> / <b>下一篇</b>',
          '「下一篇」即保存：勾选了数据库→保存关联；未勾选→自动记为<b>确认无关联</b>（原「跳过」「确认无关联」按钮取消）',
          '最后一篇的按钮变为「✓ 完成标注」，保存后提示本次标注结束',
          '<b>每篇论文最多关联 4 个数据库</b>：勾满后其余选项自动禁用，后端同步校验；论文列表的「关联」编辑弹窗同样受限'
        ]
      },
      {
        title: '标注进度与快速跳转',
        items: [
          '进度显示改为「<b>已标注 x / n 篇</b>」：x 为最近一次标注推进到的篇数，返回修改不回退',
          '本批 n 篇标完之前可随时「上一篇」返回修改，之前的勾选会正确回显',
          '新增<b>论文跳转下拉列表</b>：显示每篇的标注状态（✅/⭕）和标题，支持搜索，选中直接跳转',
          '跳回修改后可一键「<b>回到最近未标注</b>」，无需逐篇点回去'
        ]
      },
      {
        title: '标注批次持久化',
        items: [
          '一轮 n 篇标完之前，<b>切换模块 / 关闭页面 / 重启浏览器</b>再进入都恢复原批次：还是 x/n，自动定位到上次标到的位置',
          '批次进行中导入新论文，重新进入时追加到批次末尾（n = 原批次 + 新增）',
          'x = n 时本轮结束、批次清空，下次进入无新论文则显示"暂无待标注"',
          '批次快照存浏览器本地（按分类各一份）；换电脑 / 换浏览器时批次按当前待标注数量重新开批，已标注结果不受影响'
        ]
      },
      {
        title: '问题修复',
        items: [
          '修复数据库勾选区<b>分类名不显示</b>的问题（Element Plus 全局样式 font-size:0 导致分类标题被渲染为 0 像素，现已显式声明字号）',
          '修复返回上一篇后未做修改直接「下一篇」时误报保存失败的问题',
          '数据库目录接口增加防缓存参数，避免浏览器缓存旧数据'
        ]
      }
    ]
  },
  {
    version: 'v2.0',
    date: '2026-07-17',
    title: '数据库标注工作台 + 论文列表升级 + 高级搜索',
    current: false,
    sections: [
      {
        title: '新增「数据库标注」工作台',
        items: [
          '五个分类页签（带待办数量角标），逐篇审核：左侧论文信息，右侧 DataHub 全部 230 个数据库',
          '数据库按 DataHub 官方板块分组，支持名称 / ID 模糊搜索，已勾选项集中展示',
          '操作：确认保存（自动下一篇）、跳过、上一篇、确认无关联',
          '保存格式与 DataHub 正式库一致（数据库 ID 英文逗号分隔），后端校验 ID 合法性',
          '被送回的论文带 🔄 重新标注标记，并预勾选原有关联供修改'
        ]
      },
      {
        title: '论文列表升级',
        items: [
          '默认展示<b>全部论文</b>（跨分类合并，带"所属分类"列），未关联的自动排前面',
          '新增"关联数据库"列：已关联（库名标签）/ 未关联 / 无关联 / 待重新标注，状态一目了然',
          '行首勾选框 + <b>批量送回标注</b>；每行「关联」按钮可单条查看 / 修改关联',
          '每行「编辑」按钮：修改论文全部字段，右侧<b>实时预览</b>最终展示效果，Content HTML 自动重新生成'
        ]
      },
      {
        title: '高级搜索',
        items: [
          '搜索范围可选：当前分类 / 全部分类',
          '组合条件：标题、作者、摘要、关键词、期刊名称',
          '发表日期按<b>年月区间</b>筛选（自动解析"2026年04期"、"June 2026"、"6-Jun-26"等格式）；转换日期精确到日',
          '关联状态筛选：全部 / 未关联 / 已关联 / 已确认无关联 / 待重新标注',
          '搜索结果同样支持勾选、批量送回标注；搜索条件保留可追加'
        ]
      },
      {
        title: '数据与环境',
        items: [
          '新增 datahub_databases 集合：230 个 DataHub 数据库目录（从 DataHub 平台同步）',
          '测试 / 正式环境隔离：本地默认连测试库 journal_papers_test，测试操作不影响正式数据',
          '论文新增辅助字段 noRelateDbs（确认无关联）、reAnnotate（待重新标注）'
        ]
      }
    ]
  },
  {
    version: 'v1.0',
    date: '2026-07-16',
    title: '系统上线',
    current: false,
    sections: [
      {
        title: 'Excel 导入',
        items: [
          '单文件上传 / 目录批量导入，自动识别中英文期刊',
          '按期刊名和学科自动分类到五个集合（国内、国际中文-会计、国际中文-金融、国际-会计、国际-金融）',
          '按官方模板自动生成 Content HTML，自动添加转换日期、学科分类、期刊缩写',
          'PaperID 自动去重，单行失败不影响其他行'
        ]
      },
      {
        title: '论文浏览',
        items: [
          '按集合分类查看、分页展示、标题关键字搜索',
          '论文详情：结构化数据 / HTML 原文预览 / HTML 源码'
        ]
      },
      {
        title: '部署',
        items: [
          '正式环境上线：http://127.0.0.1:8080（内网）',
          '技术栈：Spring Boot 2.7.18 + Java 11 + Vue 3 + Element Plus + MongoDB，单 jar 部署'
        ]
      }
    ]
  }
]
</script>

<style scoped>
.changelog-page h2 {
  margin-bottom: 24px;
}

.version-timeline {
  max-width: 900px;
  padding-left: 8px;
}

.version-timeline :deep(.el-timeline-item__timestamp) {
  font-size: 16px;
  font-weight: bold;
  color: #303133;
}

.version-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.version-title {
  font-size: 16px;
  font-weight: bold;
  color: #409eff;
}

.section {
  margin-top: 10px;
}

.section-title {
  font-weight: bold;
  color: #303133;
  margin-bottom: 4px;
}

.section ul {
  margin: 0;
  padding-left: 20px;
}

.section li {
  line-height: 1.9;
  color: #606266;
}
</style>
