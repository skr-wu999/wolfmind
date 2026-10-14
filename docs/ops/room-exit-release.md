# 房间退出修复的发布与验收

2026-10-14。已按所有者授权由 Codex 构建、上传并部署到阿里云，实际结果见 [发布记录](releases/20261014-fix2.md)。下文保留重复发布时的操作步骤。此版也包含此前 HTTP UUID 与发言阶段代码修复。
后端和前端必须一起更新。现有 Ubuntu 部署目录 `/opt/wolfmind/deploy`，保留 Redis/SQLite 卷、密钥及 Nginx 80 端口上的静态网站。

## 本地构建和上传

启动 Docker Desktop 后，在 Windows PowerShell 执行。任何一步失败都会停止。

```powershell
& {
  Set-Location D:\MyProject\wolfmind
  docker info --format '{{.OSType}} {{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker engine is not ready.' }
  docker build --platform linux/amd64 -f deploy/Dockerfile.backend --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public -t wolfmind-backend:20261014-fix2 .
  if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
  docker build --platform linux/amd64 -f deploy/Dockerfile.frontend -t wolfmind-frontend:20261014-fix2 .
  if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
  docker save -o D:\MyProject\wolfmind-images-20261014-fix2.tar wolfmind-backend:20261014-fix2 wolfmind-frontend:20261014-fix2
  if ($LASTEXITCODE -ne 0) { throw 'Image export failed.' }
  Get-FileHash -LiteralPath D:\MyProject\wolfmind-images-20261014-fix2.tar -Algorithm SHA256 -ErrorAction Stop
}
```

成功后上传新文件：

```powershell
scp D:\MyProject\wolfmind-images-20261014-fix2.tar root@39.108.167.114:/tmp/
```

## 服务器更新

选没有真人活动局的维护窗口，更新前按既有流程备份数据、记录当前镜像标签。
本地 SHA256 与服务器输出须一致。

```bash
sha256sum /tmp/wolfmind-images-20261014-fix2.tar
sudo docker load -i /tmp/wolfmind-images-20261014-fix2.tar
cd /opt/wolfmind/deploy
sudo cp docker-compose.aliyun.yml docker-compose.aliyun.yml.before-fix2
sudo nano docker-compose.aliyun.yml
```

在原有 backend 和 frontend 服务中，分别把 image 改为 `wolfmind-backend:20261014-fix2`、`wolfmind-frontend:20261014-fix2`。
保留原有端口、资源限制和环境变量，保存退出。宽限/扫描默认值已经内置，旧 Compose 也能工作。
如需修改，在 backend 原有 environment 中增加：

```yaml
WOLFMIND_ROOM_DISCONNECT_GRACE_MS: "120000"
WOLFMIND_ROOM_RECONCILE_MS: "30000"
```

这两项分别是断线宽限 2 分钟、后台检查每 30 秒。服务器重启后重新给予宽限。
仅向服务器 `.env` 添加变量不会自动生效，Compose 必须显式透传它们。

```bash
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml config --quiet
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml up -d --no-build --pull never --no-deps --force-recreate backend frontend
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml ps
curl -fsS http://127.0.0.1:8080/api/v1/health
```

健康未就绪时检查 backend 日志；失败按记录的原镜像标签回退并重建这两个服务。
不要执行 `down -v`，不要用 `.env.example` 覆盖服务器密钥。

## 两账号验收

1. Ctrl+F5 刷新两个浏览器，分别登录，新建并加入同一房间，准备后补 AI 开局。
2. 一个账号点击“退出房间并返回大厅”，确认后返回大厅；另一个账号仍可继续，大厅显示真人 1、AI 8。
3. 最后一个账号退出，房间从大厅消失；其他大厅页面可等待最多 15 秒或点击刷新。
4. 按原 gameId 查看本局记录，应为中止、NO_HUMANS、不计胜负；回放仍可访问。
5. 正常打完的游戏留在结算页供查看，房间自动关闭。退出、回放、AI 分析入口清楚可见。
6. 刷新页面或短暂断线不立即退出；恢复订阅取消离线计时。全部真人离线且超过宽限后，后台关闭普通房间。

首次离线观察可能等待一个检查周期；死连接还要经过已有 WS 心跳检测，因此关闭时间不是精确的 120 秒。
同账号另一个标签页仍订阅该房间/对局，账号视为在线。观战者不维持其他成员在线。
独立 AI 竞技场/实验室继续运行，不因没有真人被关闭。关闭房间不删除历史记录。

本地验收记录见 [房间退出验收](../review/room-lifecycle-acceptance.md)。真实模型与服务器更新后的验收需按上述步骤执行。
