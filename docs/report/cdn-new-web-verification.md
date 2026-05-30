# cdn_new Coordinator Web 验证报告

## 摘要

- 验证时间：2026-05-30
- 访问方式：SOCKS5 代理 `127.0.0.1:6699`
- 验证对象：3 台 coordinator 的 `/hosts` 与 `/api/hosts`
- 验证结论：符合预期
- 主要证据：3 个 coordinator 均返回 HTTP 200，API 均包含 50 台 host 且全部 `alive`，Web 页面均渲染 50 个 Windows Phone 风格磁贴。

## Coordinator 地址

- `http://[fdbd:dc05:11:634::45]:9966`
- `http://[fdbd:dc05:13:10c::40]:9966`
- `http://[fdbd:dc07:0:810::44]:9966`

## 执行命令

```bash
for h in 'fdbd:dc05:11:634::45' 'fdbd:dc05:13:10c::40' 'fdbd:dc07:0:810::44'; do
  safe=$(printf '%s' "$h" | tr ':' '_')
  curl -g -sS \
    --proxy socks5h://127.0.0.1:6699 \
    --max-time 10 \
    -D ".tmp/web-verify/${safe}-hosts.headers" \
    -o ".tmp/web-verify/${safe}-hosts.html" \
    -w 'http_code=%{http_code} size=%{size_download}\n' \
    "http://[$h]:9966/hosts"

  curl -g -sS \
    --proxy socks5h://127.0.0.1:6699 \
    --max-time 10 \
    -D ".tmp/web-verify/${safe}-api.headers" \
    -o ".tmp/web-verify/${safe}-api.json" \
    -w 'http_code=%{http_code} size=%{size_download}\n' \
    "http://[$h]:9966/api/hosts"
done
```

## HTTP 结果

| Coordinator | `/hosts` | `/api/hosts` | HTML Size | API Size |
| --- | --- | --- | ---: | ---: |
| `fdbd:dc05:11:634::45` | 200 | 200 | 31089 | 23508 |
| `fdbd:dc05:13:10c::40` | 200 | 200 | 31089 | 23508 |
| `fdbd:dc07:0:810::44` | 200 | 200 | 31088 | 23508 |

## 内容校验

| Coordinator | Host Count | Alive | Expired | Tile Count | `tile-grid` | Windows Phone 文案 | Auto Refresh |
| --- | ---: | ---: | ---: | ---: | --- | --- | --- |
| `fdbd:dc05:11:634::45` | 50 | 50 | 0 | 50 | yes | yes | yes |
| `fdbd:dc05:13:10c::40` | 50 | 50 | 0 | 50 | yes | yes | yes |
| `fdbd:dc07:0:810::44` | 50 | 50 | 0 | 50 | yes | yes | yes |

校验脚本：

```bash
python3 - <<'PY'
import json
from pathlib import Path
base = Path('.tmp/web-verify')
for api in sorted(base.glob('*-api.json')):
    data = json.loads(api.read_text())
    alive = sum(1 for x in data if x.get('status') == 'alive')
    expired = sum(1 for x in data if x.get('status') == 'expired')
    html = Path(str(api).replace('-api.json','-hosts.html')).read_text()
    print(api.name)
    print('  hosts=', len(data), 'alive=', alive, 'expired=', expired)
    print('  html_tile_count=', html.count('class="tile alive"') + html.count('class="tile expired"'))
    print('  has_tile_grid=', 'tile-grid' in html)
    print('  has_windows_phone_text=', 'Windows Phone style host tiles' in html)
    print('  has_auto_refresh=', 'http-equiv="refresh" content="5"' in html)
PY
```

## 判断

Web 展示符合当前设计预期：

- 可通过 SOCKS5 代理访问 IPv6 coordinator。
- 页面能展示所有 50 台 agent host。
- 页面包含 Windows Phone 风格磁贴布局所需的 `tile-grid` 和 `tile` 元素。
- 每台 host 都以 `alive` 状态渲染。
- 页面包含 5 秒自动刷新，适合观察心跳状态变化。
- 3 个 coordinator 的 host 视图一致，均为 50 台。

## 后续建议

- 增加页面顶部统计信息，例如 total、alive、expired、coordinator id。
- 增加前端搜索或按 zone/role/status 过滤。
- 增加 endpoint 健康页，例如 `/healthz`，便于负载均衡和自动化巡检。
