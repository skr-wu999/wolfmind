# HTTP 操作编号与 AI 发言修复的发布步骤

适用：现有阿里云 Ubuntu 部署 `/opt/wolfmind/deploy`，使用基础 Compose + `docker-compose.aliyun.yml`，
已有 Redis/SQLite 数据卷与服务器 `.env`。2026-10-14 本地验证已完成；以下构建、上传和服务器更新尚未执行。

两个修复：HTTP 下真人操作使用 Web Crypto 生成 UUID；AI 规则兜底、模型输出与预览不直接显示 DAY_DISCUSS 等内部阶段代码。
更新后的新发言生效，已提交的旧发言记录继续保留原内容。发布使用新标签，避免把旧镜像误当成修复版。

## 1. Windows PowerShell 本地构建并导出

开启 Docker Desktop，按以下整块执行。失败会在块内立即停止，不继续导出。

```powershell
& {
  Set-Location D:\MyProject\wolfmind
  docker info --format '{{.OSType}} {{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker engine is not ready.' }
  docker build --platform linux/amd64 -f deploy/Dockerfile.backend --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public -t wolfmind-backend:20261014-fix1 .
  if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
  docker build --platform linux/amd64 -f deploy/Dockerfile.frontend -t wolfmind-frontend:20261014-fix1 .
  if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
  docker save -o D:\MyProject\wolfmind-images-20261014-fix1.tar wolfmind-backend:20261014-fix1 wolfmind-frontend:20261014-fix1
  if ($LASTEXITCODE -ne 0) { throw 'Image export failed.' }
  Get-FileHash -LiteralPath D:\MyProject\wolfmind-images-20261014-fix1.tar -Algorithm SHA256 -ErrorAction Stop
}
```

这里只导出更新的两个镜像；服务器继续使用已部署的 Redis。不要用之前的 `wolfmind-images.tar` 代替新文件。

构建成功并取得哈希后上传：

```powershell
scp D:\MyProject\wolfmind-images-20261014-fix1.tar root@39.108.167.114:/tmp/
```

如使用 SSH 密钥或非默认端口，按原有连接方式增加 `-i` / `-P` 参数。

## 2. 服务器更新（维护窗口）

保存当前镜像标签与配置，按已建立的备份流程备份数据；保留原 `.env`、端口与资源限制。
在没有真人活动局的维护窗口执行，更新会短暂断开连接。服务器哈希应与本地一致。

```bash
sha256sum /tmp/wolfmind-images-20261014-fix1.tar
sudo docker load -i /tmp/wolfmind-images-20261014-fix1.tar
cd /opt/wolfmind/deploy
sudo cp docker-compose.aliyun.yml docker-compose.aliyun.yml.before-fix1
sudo nano docker-compose.aliyun.yml
```

仅把覆盖配置中两处 image 改成：

```yaml
image: wolfmind-backend:20261014-fix1
```

```yaml
image: wolfmind-frontend:20261014-fix1
```

分别放在 backend 与 frontend 原有位置，不另建同名服务。保存：Ctrl+O、Enter、Ctrl+X。

配置验证成功后再更新：

```bash
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml config --quiet
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml up -d --no-build --pull never --no-deps --force-recreate backend frontend
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml ps
curl -fsS http://127.0.0.1:8080/api/v1/health
```

若健康尚未就绪，检查后端日志并等待启动；持续异常按记录的回退步骤处理，不把启动失败当作发布成功。
不要执行 `down -v`，不要用 `.env.example` 覆盖服务器的密钥和配置。

## 3. 浏览器确认

访问 `http://39.108.167.114:8080`，Ctrl+F5 强制刷新并重新登录/同步。新开一局，
在真人自己的回合提交发言，验证没有 randomUUID 报错；再检查投票/技能提交。
新 AI 发言应直接表达观点，规则兜底同样不带 DAY_DISCUSS 开头。
内部日志和接口的 phase 仍保留机器标识，这是游戏协议；旧对局已记录的旧发言不自动改写。

本地验收：前端 394 项、类型检查与构建；真实 Chromium 非安全 HTTP 下 100 个 UUID 和三类操作；
后端 988 项，0 失败/错误，性能基准 1 跳过，其中真实 Redis 42 项实际执行。
这些结果不替代服务器更新后的上述验收。
