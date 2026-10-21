# 期刊论文标注与管理系统

把抓取来的期刊论文（Excel）导入 MongoDB，在线浏览 / 搜索 / 标注，把论文与「数据库」建立关联，预览渲染后的正文，导出 Excel，并把标注结果推送到外部 SQL Server。

Spring Boot + Vue 3 的前后端分离项目，前端产物最终打进后端 jar，单进程对外提供页面和 API。

## 技术栈

| 层 | 用的什么 |
|---|---|
| 后端 | Spring Boot 2.7.18 / Java 11 / Spring Data MongoDB / Apache POI 5.2.5 / jsoup 1.17.2 / jTDS 1.3.1（SQL Server）/ Lombok |
| 前端 | Vue 3 + Vite 5 + Element Plus + axios（`baseURL: '/api'`，同源相对路径） |

## 目录

```text
backend/                    Spring Boot
  src/main/java/com/journal/
    controller/             PaperController / AnnotationController / ExternalUploadController
    service/                ExcelImportService / ContentGenerator / PaperDedupe / PaperFilters / JournalMetadataService / ExternalUploadService
    model/                  Paper / DataHubDatabase
    repository/             PaperRepository
    config/                 WebConfig / SpaForwardController（SPA 路由回退）
  src/main/resources/
    application.yml         数据源与外部推送配置
    static/                 SPA 落点（前端产物放这里）
  src/test/java/            PaperDedupeTest / ExternalUploadMergeTest
frontend/                   Vue 3 + Vite
  src/views/                PaperList / PaperImport / Annotation / ExportPreview / UploadToExternal / Changelog
  src/components/           DbSelector
  src/composables/          useDataHubDb
scripts（仓库根）             deploy.sh / deploy.ps1 / auto_deploy.py / tls-proxy.py / import-datahub-catalog.py
```

## 本地启动

后端（8080）：

```bash
cd backend
mvn spring-boot:run
```

前端开发服务器（3000，`/api` 代理到 8080）：

```bash
cd frontend
npm install
npm run dev
```

## 配置

`backend/src/main/resources/application.yml`：

```yaml
spring.data.mongodb.uri: mongodb://127.0.0.1:27017/journal_papers   # 改这里
external.sqlserver:                                                  # 「上传外网」目标库
  host: ${EXTERNAL_SQL_HOST:127.0.0.1}
  user: ${EXTERNAL_SQL_USER:sa}
  password: ${EXTERNAL_SQL_PASSWORD:}                                # 密码只走环境变量
```

- 默认连 `journal_papers_test`，生产用 `--spring.profiles.active=prod` 切 `journal_papers`。
- 环境变量样例见 `.env.example`（`.env` 不入库，见 `.gitignore`）。

## 打包成单 jar

前端产物必须先进后端 `static/`，否则 jar 里还是旧页面。三步都要做：

```bash
cd frontend && npm run build
cp -r frontend/dist/* ../backend/src/main/resources/static/
cd ../backend && mvn clean package -DskipTests
```

> `backend/src/main/resources/static/db-view.html` 不是 Vite 产物，别整目录删。

## 测试

```bash
cd backend && mvn test
```

- `PaperDedupeTest` —— 按 PaperID 去重，重复导入不产生副本。
- `ExternalUploadMergeTest` —— 上传外网时的合并写回逻辑。

## 其他文档

- `使用文档.md` —— 功能清单与操作说明
- `v1.0-上线日志.md` / `v2.0-更新日志.md` / `v2.1-更新日志.md` —— 版本记录
- `CLAUDE.md` —— 与 AI 协作时的项目约定

## 说明

脱敏演示版：公司名、域名、内网地址、账号密码、同事姓名与客户学校名均已替换为占位值；仓库历史已重建为单次提交。`application.yml` 里的连接串是占位，直接跑连不上任何真实库。
