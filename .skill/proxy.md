# Proxy Usage

## 代理用途

- 提交代码、访问 GitHub、`git push`、`git fetch`、下载公网依赖时，使用 SOCKS5 代理 `127.0.0.1:2080`。
- 访问 `cdn_new` 集群内 Pulse coordinator Web/API 时，使用 SOCKS5 代理 `127.0.0.1:6699`。

## Git 示例

```bash
git \
  -c http.proxy=socks5h://127.0.0.1:2080 \
  -c https.proxy=socks5h://127.0.0.1:2080 \
  push origin main
```

## Coordinator 示例

```bash
curl -g -sS \
  --proxy socks5h://127.0.0.1:6699 \
  "http://[fdbd:dc05:11:634::45]:9966/hosts"
```

```bash
curl -g -sS \
  --proxy socks5h://127.0.0.1:6699 \
  "http://[fdbd:dc05:11:634::45]:9966/api/hosts"
```

## 注意事项

- 不要用 `127.0.0.1:6699` 推送代码。
- 不要用 `127.0.0.1:2080` 访问 coordinator。
- IPv6 URL 需要使用方括号，例如 `http://[fdbd:dc05:11:634::45]:9966/hosts`。
- `curl` 访问 IPv6 URL 时建议加 `-g`，避免方括号被当作 glob 解析。
