# task-output-streaming 部署报告

## 摘要

- 运行模式：`central-runtime`
- Runtime 仓库：`/Users/bytedance/Documents/gitlab/olap-toolbox`
- Project 仓库：`/Users/bytedance/Documents/01_Projects/pulse`
- Inventory：从线上 coordinator `/api/hosts` 导出，`471` 台
- 安装目录：`/data24/otf/pulse`
- Coordinator URL：`fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44`
- 目标 JAR SHA：`cc6bbfedcbcedfb8bc72969d5eca7118c695c519c74286270479d52a57df14fb`
- 部署结果：`467/471` 已通过强校验，`4/471` 被 root SSH/GSSAPI 权限阻断
- 说明：`pulse-cdn-new-deploy.sh` 在部分 `ssh/scp` 失败时仍输出 `status=ok`，因此本报告以 `shell-helper` 强校验结果为准

## 代码版本

- `23bb596 Classify group heartbeat flush triggers`
- `c219633 Implement task output streaming`
- `bc020f7 Require SPSC queues for task streaming`
- `5c1e24c Define task stream heartbeat queues`
- `c32acd5 Remove trace id from stream design`

## 本地验证

- 前端构建：`src/main/frontend/npm run build` 通过
- 后端构建：`mvn package` 通过
- 自动化测试：`28` 个测试通过
- 产物：`target/pulse-0.1.0-SNAPSHOT.jar`

## 执行记录

### Scope

- Inventory：`.tmp/auto-ops/task-output-streaming/pulse-all.ini`
- 范围：`471` 台
- 来源：通过 socks proxy 访问线上 coordinator `/api/hosts`

### Deploy

- 全量部署并发：`--parallel 8`
- 初次 deploy summary：`total=471 ok=471 failed=0`
- 初次 verify summary：`total=471 ok=471 failed=0`
- 问题：日志包含大量 `Permission denied` / `scp: Connection closed`，summary 不可信

### Strong Verify 1

- 命令：`scripts/shell-helper.sh`
- 校验项：`sha256sum /data24/otf/pulse/bin/pulse.jar`
- 期望 SHA：`cc6bbfedcbcedfb8bc72969d5eca7118c695c519c74286270479d52a57df14fb`
- 结果：`total=471 ok=97 failed=374`

### Redeploy 374

- Demand：`374/374 ok`
- 定向补部署：`.tmp/auto-ops/task-output-streaming-sha/failed-hosts.txt`
- 中途强校验：`total=374 ok=215 failed=159`
- 补部署完成后全量强校验：`total=471 ok=370 failed=101`

### Redeploy 101

- Demand：`101/101 ok`
- 定向补部署：`.tmp/auto-ops/task-output-streaming-sha-final-1/failed-hosts.txt`
- 全量强校验：`total=471 ok=459 failed=12`

### Redeploy 12

- Demand：`12/12 ok`
- 定向补部署：`.tmp/auto-ops/task-output-streaming-sha-final-2/failed-hosts.txt`
- 结果：`dc08:0:131e` 段 8 台补部署成功
- 剩余 4 台持续 root SSH/GSSAPI 权限拒绝

## 最终强校验

低并发复核 `.tmp/auto-ops/task-output-streaming-sha-final-7-scope/failed-hosts.txt`：

- `fdbd:dc05:2:71c::18` 至 `fdbd:dc05:2:71c::25`：均为目标 SHA，`pulse-agent.service` active
- `fdbd:dc05:5:913::30`：`Permission denied (gssapi-keyex,gssapi-with-mic)`
- `fdbd:dc05:2:71d::71`：`Permission denied (gssapi-keyex,gssapi-with-mic)`
- `fdbd:dc05:2:90b::210`：`Permission denied (gssapi-keyex,gssapi-with-mic)`
- `fdbd:dc05:2:90b::211`：`Permission denied (gssapi-keyex,gssapi-with-mic)`

## 阻断项

- `fdbd:dc05:5:913::30`
- `fdbd:dc05:2:71d::71`
- `fdbd:dc05:2:90b::210`
- `fdbd:dc05:2:90b::211`

这些节点 `demand` 均返回成功，但 root SSH 仍然拒绝登录，部署脚本无法传输 JAR，也无法读取远端 SHA。需要修复 root GSSAPI/orthrus 临时权限后，使用同一 JAR 和同一 deploy 命令定向重跑。

## 后续动作

- 修复 `docs/script/pulse-cdn-new-deploy.sh` 和 `docs/script/pulse-cdn-new-verify.sh`：本地 `ssh/scp` 失败必须返回非零，避免 summary 误报。
- 权限修复后，对 4 台阻断节点执行 demand、deploy、强校验。
- 最终再跑一次 471 台强校验，目标为 `471/471` 目标 SHA。
