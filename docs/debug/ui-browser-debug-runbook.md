# UI Browser 调试手册

本文记录使用浏览器真实布局调试 Pulse UI 的方法，重点用于定位轮询刷新、模态框、Monaco、grid/flex 和滚动状态问题。

## 适用场景

- UI 问题依赖真实布局，单看代码或截图无法定位。
- CSS 在本地看起来合理，但部署到 coordinator 后页面仍然错位。
- 涉及 Monaco、grid/flex、modal、滚动条或轮询刷新。
- 轮询刷新导致页面或组件滚动位置被重置。

## 调试原则

- 不要只看静态 CSS，必须在浏览器里测量真实 DOM 盒模型。
- 不要只修 `overflow` 表象，要找到是谁抢走了高度或宽度。
- 先数清楚直接子节点数量，再核对显式 grid row 定义。
- `overflow: auto` 只作为末手段，不能让用户靠滚多个小面板理解状态。
- 轮询 UI 必须保留编辑器和滚动状态，不能每次刷新都重建组件。
- UI 由远端 coordinator 提供时，最终验证必须基于部署后的真实页面。
- 每个独立修复点都要立即提交并推送，保证代码、jar、页面状态可追溯。

## 准备

先将本地端口转发到一个 coordinator：

```bash
ssh -N -L 127.0.0.1:9967:127.0.0.1:9966 fdbd:dc05:11:634::45
```

启动开启 DevTools Protocol 的 headless Chrome：

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless=new \
  --disable-gpu \
  --remote-debugging-port=9222 \
  '--remote-allow-origins=*' \
  --user-data-dir=/tmp/pulse-chrome-debug \
  --no-first-run \
  --no-default-browser-check \
  about:blank
```

确认 DevTools 正常启动：

```bash
curl -s http://127.0.0.1:9222/json/version
```

## CDP 布局探测

可用一个基于 `websocket-client` 的 Python 小脚本驱动 Chrome；如未安装先执行：

```bash
python3 -m pip install --user websocket-client
```

探测脚本建议完成以下动作：

- 打开 `http://127.0.0.1:9967/hosts`。
- 设置真实 viewport，例如 `1024x588`。
- 点击第一个可用的 `任务` 按钮或目标 host 卡片。
- 等待 `#task-modal.open`。
- 需要时点击 `#task-run`，等待 JSON 输出。
- 测量 modal、shell、workspace、execution card、completion card、output、Monaco、关闭按钮的 `getBoundingClientRect()`。
- 测量 `.task-sidebar` 与 `.task-workspace` 宽度比例，确认右侧 completion 区约为左侧信息区的 `1.618` 倍。
- 比较 output 底部是否溢出 completion/panel。
- 检查按钮 computed style，确认任务按钮和工具栏按钮为水平排布且不折行。
- 检查页面可见文本和关键 DOM 属性，确认只出现 IPv6，不出现 hostname 或内部域名。
- 检查 computed style 和 HTML 文本，确认没有高光、模糊、渐变、扫光、水波、果冻等视觉效果。
- 下发任务后观察 `state.async_tasks` 对应的 `agent 执行中`、`已接收`、`执行中` 是否在 completion 返回前出现。
- 截图做最终视觉确认。

建议采集的指标示例：

```javascript
(() => {
  const q = s => document.querySelector(s);
  const rect = el => {
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return {
      x: r.x,
      y: r.y,
      w: r.width,
      h: r.height,
      top: r.top,
      bottom: r.bottom,
      left: r.left,
      right: r.right
    };
  };
  const out = q('#task-output');
  const comp = q('.completion-card');
  const panel = q('.task-panel');
  return {
    viewport: {w: innerWidth, h: innerHeight},
    panel: rect(panel),
    shell: rect(q('.task-shell')),
    workspace: rect(q('.task-workspace')),
    sidebar: rect(q('.task-sidebar')),
    execution: rect(q('.execution-card')),
    completion: rect(comp),
    completionMeta: rect(q('#task-completion-meta')),
    outputTabs: rect(q('.task-output-tabs')),
    output: rect(out),
    monaco: rect(q('#task-output .monaco-editor')),
    closeX: rect(q('#task-close-x')),
    outputClient: out ? {
      clientHeight: out.clientHeight,
      scrollHeight: out.scrollHeight,
      clientWidth: out.clientWidth,
      scrollWidth: out.scrollWidth
    } : null,
    goldenRatio: q('.task-sidebar') && q('.task-workspace') ? {
      workspaceToSidebar: q('.task-workspace').getBoundingClientRect().width /
        q('.task-sidebar').getBoundingClientRect().width,
      panelToViewportHeight: panel ? panel.getBoundingClientRect().height / innerHeight : null
    } : null,
    taskButtons: [...document.querySelectorAll('.run-button, .task-toolbar button')].map(el => {
      const style = getComputedStyle(el);
      return {
        text: el.textContent.trim(),
        whiteSpace: style.whiteSpace,
        writingMode: style.writingMode,
        width: el.getBoundingClientRect().width,
        height: el.getBoundingClientRect().height
      };
    }),
    visibleTextHasHostname: /[a-z0-9-]+\\.byted\\.org/i.test(document.body.innerText),
    htmlHasHighlightStyle: /box-shadow|backdrop-filter|gradient|liquid-flow|water-ripple|jelly-scroll/i.test(document.documentElement.outerHTML),
    agentTaskStatusVisible: !![...document.querySelectorAll('.task-progress-row')]
      .find(el => /agent 执行中|已接收|执行中/.test(el.innerText)),
    overflow: out && comp ? {
      outputBelowCompletion: out.getBoundingClientRect().bottom > comp.getBoundingClientRect().bottom + 1,
      outputBelowPanel: out.getBoundingClientRect().bottom > panel.getBoundingClientRect().bottom + 1,
      horizontal: out.scrollWidth > out.clientWidth + 1
    } : null
  };
})()
```

## 常见故障

Run UI 曾经出现过以下回归：

- completion card 有四个直接子节点，但 CSS 只定义了三行 grid。
- tabs 抢占了 `1fr` 行，editor 掉进隐式第四行。
- editor 在真实 viewport 下只剩几像素高度。
- completion metadata 和 execution card 占用了过多垂直空间。
- modal 外层装饰太厚，真正工作区太小。
- 轮询刷新时重建 Monaco，导致滚动位置重置。

浏览器测量可以直接暴露问题：

- 某个坏状态下 `#task-output` 高度只有 `2px`。
- Monaco 高度只有约 `5px`。
- grid 修复前，`outputBelowCompletion=true` 且 `outputBelowPanel=true`。
- 后续视觉收敛后虽然不再溢出，但 editor 仍然偏小，因此布局进一步改造成工作台模式。

## 修复模式

- 每个直接 grid 子节点都要有显式 grid row。
- 低优先级长文本不要放在主纵向路径里。
- 用紧凑指标条替代大段详情区。
- 空执行队列必须收紧，不能吞掉主工作区高度。
- 头部要像控制台，操作和状态一眼可见。
- editor 必须拿到主宽度和可预期剩余高度。
- 运维 modal 应尽量接近全屏工作台。
- 关闭按钮使用 macOS 风格窗口控制点，放在独立标题栏内，不与业务 UI 重叠。

## Monaco 轮询规则

- 输出未变化时，不要调用 `dispose()` 或 `setValue()`。
- 输出变化时，必须保存并恢复 Monaco 视图状态：

```javascript
const viewState = outputEditor.saveViewState();
const scrollTop = outputEditor.getScrollTop();
const scrollLeft = outputEditor.getScrollLeft();
model.setValue(text);
outputEditor.layout();
if (viewState) {
  outputEditor.restoreViewState(viewState);
}
outputEditor.setScrollPosition({scrollTop, scrollLeft});
```

- 如果异步格式化 JSON，也要在格式化前后恢复滚动位置。

## `/hosts` 页面附加检查

浏览器调试 `/hosts` 时，除布局外还要确认：

- 顶部和任务面板文案都是简洁中文。
- 页面定位是“心跳平台”，不是课程、校园、实验室或教育产品。
- 页面描述不重复堆叠，围绕任务、集群、资源、监控、告警保留想象空间。
- 中文大标题不使用过密负字距，视觉留白接近 Apple 官网风格。
- 所有可见节点标识只使用 IPv6；不出现 hostname、FQDN、`.byted.org` 或 `data-agent-id`。
- 页面和任务面板不使用高光、发光、模糊、渐变、扫光、水波或果冻动效。
- 卡片不展示瞬时 `Load`，只展示固定窗口 `5min AVG`。
- `5min AVG` 在窗口开始时只采样一次，窗口内不继续累计或更新排序权重。
- Run UI 面板高度约为 viewport 的 `61.8%`，左侧信息区与右侧 completion 区宽度约为 `1 : 1.618`。
- Run UI 标题和任务操作并排，不出现“任务执行/执行任务”重复堆叠。
- 关闭按钮位于窗口标题栏左侧，形态类似 macOS 窗口控制点，不覆盖内容卡片。
- 任务按钮水平排列，文字不竖排、不折行。
- agent 执行任务后，completion 返回前能够看到 heartbeat 中的 `accepted/running` 状态。
- 轮询刷新不会让页面滚动位置、卡片内部滚动位置或任务输出滚动位置回到起点。

## 构建、部署、验证

本地构建：

```bash
mvn -q test
mvn -q -DskipTests package
shasum -a 256 target/pulse-0.1.0-SNAPSHOT.jar
```

部署到三个 coordinator：

```bash
PATH="/opt/homebrew/opt/coreutils/libexec/gnubin:$PATH" \
AUTO_OPS_ARTIFACT_ROOT=/Users/david/Documents/projects/pulse/.tmp/auto-ops/coordinator-ui-debug \
AUTO_OPS_REPORT_DIR=/Users/david/Documents/projects/pulse/docs/report \
  bash scripts/call.sh \
    -f /Users/david/Documents/projects/pulse/docs/script/pulse-cdn-new-deploy.sh \
    -t cdn_new \
    --limit-file /Users/david/Documents/projects/pulse/.tmp/auto-ops/coordinator-ui-keyed/coordinators.txt \
    --parallel 3 \
    --timeout 240 \
    --max-hosts 3 \
    --yes \
    -- /Users/david/Documents/projects/pulse/target/pulse-0.1.0-SNAPSHOT.jar \
       'fdbd:dc05:11:634::45,fdbd:dc05:13:10c::40,fdbd:dc07:0:810::44' \
       /data24/otf/pulse - cdn_new
```

校验远端 jar 和服务：

```bash
for h in 'fdbd:dc05:11:634::45' 'fdbd:dc05:13:10c::40' 'fdbd:dc07:0:810::44'; do
  echo "=== $h ==="
  ssh -o ConnectTimeout=10 "$h" \
    'sha256sum /data24/otf/pulse/bin/pulse.jar; systemctl is-active pulse-coordinator.service'
done
```

对已部署 coordinator 重新执行 CDP 探测。只有满足以下条件，修复才算完成：

- `outputBelowCompletion=false`
- `outputBelowPanel=false`
- `horizontal=false`
- close button 有可见 bounding box
- editor/output 在目标 viewport 中具有足够高度
- JSON 输出在轮询期间保持滚动位置
- `goldenRatio.workspaceToSidebar` 接近 `1.618`
- `visibleTextHasHostname=false`
- `htmlHasHighlightStyle=false`
- 任务下发后 `agentTaskStatusVisible=true`

## 提交纪律

每个独立 UI 修复完成后都要立即提交：

```bash
git add src/main/java/com/bytedance/pulse/HostTilesPage.java
git commit -m "Describe the UI fix"
git push
```

不要把多个无关 UI 尝试攒在一起。远端 UI 调试时，部署 jar 的 SHA、浏览器测量结果和 git commit 必须能一一对应。
