# 期刊论文管理系统

Spring Boot 2.7.18 + Vue 3 + MongoDB + jTDS。代码路径 `.\journal-system\`。

## 启动项目

### 开发模式（改代码后自动热重载）
```bash
cd backend && mvn spring-boot:run
```

### 只会改前端时
```bash
cd frontend && npm run dev
# 前端开发服务器，API 代理到 8080
```

## 首次使用需要先构建一次
本仓库不含编译产物（`backend/target/`、`frontend/dist/`、
`backend/src/main/resources/static/assets/` 都不入库），先按下文「编译命令」跑一遍。

## 编译命令（仅改代码后执行）
```bash
# 只编译后端
cd backend && mvn clean package -DskipTests -q

# 改了前端：构建 + 同步到 static + 重新打包（三步都要做，见下方说明）
cd frontend && npm run build
cd .. && rm -rf backend/src/main/resources/static/assets \
  && cp -r frontend/dist/assets backend/src/main/resources/static/assets \
  && cp frontend/dist/index.html backend/src/main/resources/static/index.html
cd backend && mvn clean package -DskipTests -q
```

### ⚠️ 前端改动必须手工同步 dist → static
Vite 输出到 `frontend/dist/`，但 JAR 服务的是 `backend/src/main/resources/static/`，
**两者之间没有任何自动拷贝**（vite 配置、package.json、Maven 里都没有）。

只跑 `npm run build` 再打 JAR，界面不会更新——JAR 里还是旧的前端，
而后端接口是新的，很容易误判为"改动没生效"。

同步时注意 **`static/db-view.html` 不是 Vite 产物**（v4.0 的 数据库查看页），
只能替换 `assets/` 和 `index.html`，别整目录删掉。

验证同步成功：`unzip -l backend/target/journal-system-1.0.0.jar | grep static/assets`
里的文件名哈希应与 `frontend/dist/assets/` 一致。

## 核心信息
- **端口**: 8080
- **MongoDB**: 127.0.0.1:27017，库 `journal_papers`
- **Profile**: 默认 test 库，生产用 `--spring.profiles.active=prod`
- **数据库只读原则**: 对公司数据的增删改操作必须先向用户确认

## 关键目录
- `backend/` — Spring Boot（Java 11）
- `frontend/` — Vue 3 + Vite + Element Plus
- `backend/src/main/java/com/journal/` — 主代码
- `backend/src/main/resources/application.yml` — 配置
