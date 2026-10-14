# 阿里云 Ubuntu 部署：2 核 4GB，已有 Nginx 网站

日期：2026-10-14。本文是操作说明，尚未在目标服务器执行。

## 1. 本次部署方式

本地电脑构建前后端 Docker 镜像，上传到服务器后用 Compose 启动。服务器只安装 Docker Engine 和 Compose 插件，不单独安装 Java、Maven、Node、Redis 或 SQLite。

现有 Nginx 静态网站继续使用 80，WolfMind 使用服务器的 8080。WolfMind 镜像内部也有 Nginx，它在自己的容器内监听 80，通过 Docker 映射到宿主机 8080，两者不会占用同一宿主机端口。

浏览器访问 `http://服务器公网IP:8080`；页面、REST 和 WebSocket 同源。Redis 和后端端口不对外发布。先按限定人员访问的 HTTP 内测部署，沿用项目已记录的无传输加密限制。

以下假定服务器是 Ubuntu 22.04/24.04/26.04，CPU 为 x86_64，本地为 Windows PowerShell，Docker Desktop 使用 Linux 容器。服务器先执行：

```bash
cat /etc/os-release
uname -m
free -h
df -h
sudo ss -lntp
```

如果系统版本不在上述范围，按支持该版本的 Docker 安装方法处理；如果 `uname -m` 为 aarch64，下面构建命令的 `linux/amd64` 改为 `linux/arm64`，不要上传架构不匹配的镜像。确认 8080 没有被其他进程占用，并有足够空间同时保存镜像包、解包后的镜像和持久化数据。

本文各命令按顺序在标注的电脑或服务器执行；一条失败就先解决该错误，不继续后续启动。SSH 的部署账号、公网 IP 和密钥路径按实际替换，不把私钥或模型密钥发到聊天中。

## 2. 在服务器安装 Docker

适用于用户说明的尚未安装 Docker 的 Ubuntu。安装前不要卸载或修改现有 Nginx。

这里使用阿里云公开 Docker CE 软件包镜像源的 HTTPS 地址；GPG 验签仍保留。若镜像源不可达，可改用 Docker 官方仓库。APT 软件包镜像和 Docker 容器镜像仓库是两件事，这一步不保证 Docker Hub 可访问。

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
```

```bash
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://mirrors.aliyun.com/docker-ce/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
```

```bash
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
```

参考：[阿里云 Docker/Compose 安装说明](https://www.alibabacloud.com/help/zh/ecs/user-guide/install-and-use-docker)、[Docker Ubuntu 安装说明](https://docs.docker.com/engine/install/ubuntu/)。本文没有执行卸载旧组件或删除数据目录的步骤。

## 3. 在本地电脑打包

开启 Docker Desktop，使用 Linux 容器。下面版本号 `20261014` 是示例发布标识，后续发布使用新标识。

先确认 Docker Desktop 已启动且引擎就绪。只安装 Docker 命令行工具、能运行 `docker --version`，不代表引擎正在运行。在 PowerShell 执行：

```powershell
docker info --format '{{.OSType}} {{.ServerVersion}}'
```

应输出 `linux` 与服务端版本。如果出现 `dockerDesktopLinuxEngine` 管道不存在，先从开始菜单启动 Docker Desktop，等待引擎就绪，再执行上述检查。本机已核实安装路径为 `F:\Docker\Docker Desktop.exe`，也可用 `Start-Process -FilePath 'F:\Docker\Docker Desktop.exe' -WindowStyle Hidden` 启动；其他电脑按实际安装位置处理。检查失败时不要继续 build、pull 或 save。

在 Windows PowerShell 执行：

```powershell
& {
Set-Location D:\MyProject\wolfmind
docker info --format '{{.OSType}} {{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker engine is not ready; start Docker Desktop first.' }
docker build --platform linux/amd64 -f deploy/Dockerfile.backend --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public -t wolfmind-backend:20261014 .
if ($LASTEXITCODE -ne 0) { throw 'Backend image build failed.' }
docker build --platform linux/amd64 -f deploy/Dockerfile.frontend -t wolfmind-frontend:20261014 .
if ($LASTEXITCODE -ne 0) { throw 'Frontend image build failed.' }
docker pull --platform linux/amd64 redis:8-alpine
if ($LASTEXITCODE -ne 0) { throw 'Redis image pull failed.' }
}
```

后端 Dockerfile 编译成可执行 JAR，前端 Dockerfile 执行类型检查和 Vite 构建，再放入 Nginx。前端默认使用真实接口与空 API/WS 基址，由当前页面地址推导服务器地址，不需要把公网 IP 写进前端。

镜像构建并不执行后端单元测试。发布前仍需完成独立测试，Redis 强制验收应使用隔离测试实例；不得清空本地或服务器业务数据库。最近一次测试记录不等同于目标服务器验收。

构建成功后，导出三个镜像和部署配置：

```powershell
& {
docker save -o D:\MyProject\wolfmind-images.tar wolfmind-backend:20261014 wolfmind-frontend:20261014 redis:8-alpine
if ($LASTEXITCODE -ne 0) { throw 'Image archive export failed.' }
tar -czf D:\MyProject\wolfmind-deploy.tar.gz deploy/docker-compose.yml deploy/.env.example
if ($LASTEXITCODE -ne 0) { throw 'Deployment configuration archive failed.' }
Get-FileHash D:\MyProject\wolfmind-images.tar -Algorithm SHA256 -ErrorAction Stop
}
```

PowerShell 脚本块内逐项检查原生命令退出码，失败立即停止，避免镜像未构建成功却继续导出或计算不存在文件的哈希。配置包的 tar 命令不依赖 Docker，因此它可以成功而镜像包仍未生成。

部署配置包不包含本地 `.env`、模型密钥、账号数据库或运行中对局。本次是全新服务器部署，本地旧账号和对局不会自动迁移；如需迁移，应另行使用一致性备份流程。

上传两个文件（替换账号和公网 IP）：

```powershell
scp D:\MyProject\wolfmind-images.tar D:\MyProject\wolfmind-deploy.tar.gz 部署账号@服务器公网IP:/tmp/
```

SSH 若使用非 22 端口，给 scp 加 `-P 实际端口`；使用密钥文件时加 `-i 本地密钥路径`。也可以通过已有 SFTP 工具上传到 `/tmp`，效果相同。

## 4. 在服务器导入并配置

首次部署执行：

```bash
sudo mkdir -p /opt/wolfmind
sudo tar -xzf /tmp/wolfmind-deploy.tar.gz -C /opt/wolfmind
sha256sum /tmp/wolfmind-images.tar
sudo docker load -i /tmp/wolfmind-images.tar
cd /opt/wolfmind/deploy
sudo cp .env.example .env
sudo chmod 600 .env
sudo nano .env
```

服务器 SHA256 应与本地一致。更新已有部署时保留原 `.env`，不要再次用模板覆盖密钥和配置。

先把 `.env` 中这些项设为：

```dotenv
WOLFMIND_HTTP_PORT=8080
WOLFMIND_LLM_PROVIDER=none
WOLFMIND_LAB_MAX_CONCURRENT_GAMES=1
WOLFMIND_AGENT_MAX_CONCURRENT_TURNS=2
```

这些是 2 核 4GB 上的保守起点，不是实测容量承诺。`none` 先用规则机器人验证部署；此时不发生付费模型调用。通过后再配置服务商、接口地址、API key 和实际模型名称，设置费用上限。

## 5. 为这台服务器增加 Compose 覆盖配置

在 `/opt/wolfmind/deploy` 执行，创建 `docker-compose.aliyun.yml`。该文件引用刚导入的镜像，并覆盖项目的运行资源设置，不修改现有 80 端口的网站。

```bash
sudo tee docker-compose.aliyun.yml >/dev/null <<'EOF'
services:
  backend:
    image: wolfmind-backend:20261014
    mem_limit: 1536m
    environment:
      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx768m -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
  frontend:
    image: wolfmind-frontend:20261014
    mem_limit: 128m
  redis:
    mem_limit: 512m
    command:
      - redis-server
      - --appendonly
      - "yes"
      - --appendfsync
      - everysec
      - --save
      - ""
      - --maxmemory
      - 256mb
      - --maxmemory-policy
      - noeviction
EOF
```

容器上限总计约 2.125GiB，给系统、Docker 和原网站留出余量；Java 堆不是后端全部内存，还包括线程、直接内存和元空间。实际使用需观察并调整，现有网站占用较大时应进一步减少负载。

Redis `noeviction` 避免自动淘汰未归档对局数据，达到上限会拒绝部分写入而非静默删除。必须观察内存和归档积压；不要把这个小容量设置当作可以无限开局。终局自动清理按现有完整归档与保留策略执行，不删除活动局来腾空间。

基础 Compose 的 build 字段仍存在，但以下命令显式禁用构建和拉取，使用导入的镜像，因此服务器不需要源码、Maven、Node 或访问 Docker Hub：

```bash
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml config --quiet
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml up -d --no-build --pull never
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml ps
```

不要运行带输出的 `docker compose config` 后把整份输出发给他人，它可能包含 `.env` 中的密钥。这里只使用 `--quiet` 验证配置。

## 6. 安全组与访问检查

阿里云 ECS 控制台中，在该实例关联的安全组添加入方向 TCP 8080 规则，来源先限定测试人员公网 IP；SSH 22 或实际 SSH 端口也限定管理来源。轻量服务器使用其防火墙入口。原 80 规则保持原网站用途，不开放 Redis 6379 或后端独立端口。

Docker 发布端口可能绕过 UFW 的常规规则，所以不要仅凭 UFW 配置判断 8080 的访问范围，核对阿里云安全组及实际外部访问。[阿里云安全组说明](https://www.alibabacloud.com/help/zh/ecs/user-guide/start-using-security-groups)、[Docker 防火墙注意事项](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations)。

后端启动需要时间，查看状态和最近日志：

```bash
cd /opt/wolfmind/deploy
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml logs --tail=100 backend
curl -fsS http://127.0.0.1:8080/api/v1/health
sudo docker stats --no-stream
```

健康接口应返回 HTTP 200 和依赖状态。再从浏览器访问 `http://服务器公网IP:8080`，验证注册、登录、建房、AI 补位、开局、终局、历史和刷新重连。打开浏览器开发者工具确认 `/ws` 握手成功，页面使用真实数据。也验证原来的 80 网站仍正常。

如果本机 curl 成功而公网访问失败，重点检查公网 IP、安全组和端口占用；若 502，检查后端启动、健康与容器网络。健康成功不代表完整业务验收成功，仍按收尾计划完成测试。

## 7. 接入真实模型与后续更新

部署流程通过后，用 `sudo nano /opt/wolfmind/deploy/.env` 配置实际 `WOLFMIND_LLM_PROVIDER`、`BASE_URL`、`API_KEY`、`MODEL`。DeepSeek 对应 provider 为 `deepseek`，接口与模型名称使用当前账号实际配置。`WOLFMIND_LLM_MAX_TOKENS_PER_GAME=0` 表示不限制，要根据验收预算设定非零上限，并在服务商侧设置总费用限制。

在维护窗口执行，重新创建后端和前端，让配置生效并刷新 Nginx 到后端的连接：

```bash
cd /opt/wolfmind/deploy
sudo docker compose -f docker-compose.yml -f docker-compose.aliyun.yml up -d --no-build --pull never --no-deps --force-recreate backend frontend
curl -fsS http://127.0.0.1:8080/api/v1/health
```

配置更新会短暂中断服务。不要在真人对局进行中随意更新。发布新代码时，在本地构建新版本标签、导出上传并 load，然后修改覆盖配置中的两处 image 标签，再执行更新与验收。两个版本的镜像与配置暂时都保留，用于必要的回退；数据库变更不保证可直接回退旧镜像。

Redis 和 SQLite 保存在 Compose 命名卷中，账号、历史和活动局不靠容器文件层保存。常规更新不删除数据卷；不要运行 `docker compose down -v`，它会删除这套部署的数据卷。持久化卷不等于备份，自动备份与真实恢复演练仍按收尾计划完成。

初次可以使用 IP:8080。若以后要通过另一个域名使用现有宿主机 Nginx 的 80 端口，应增加独立 server_name 反代到 `127.0.0.1:8080`，正确传递 WebSocket 升级头，并将 Docker 端口限制为本机监听。不要把整站简单挂到现有静态网站 `/wolfmind/` 子路径：目前前端路由和资源使用根路径，需要额外适配。

GitHub Actions 后续可替代本地构建与上传；本说明不要求先配置 Actions，也未修改服务器或执行镜像构建。
