# Group Leader Location-Aware Plan

## 背景

Pulse 的 group heartbeat 由 coordinator 动态下发 group plan，agent 根据 plan 进入 `direct`、`leader` 或 `follower` 模式。旧实现使用 `PULSE_GROUP_SIZE_LIMIT=7` 作为硬上限，每 7 台切一个 shard。这种规则简单，但 leader 数量随机器数线性增长，也没有明确表达“机器位置接近”的分组目标。

新规则把 group leader 看作集群内的聚合入口，而不是固定大小分片：

- 集群机器数小于 5 时，不启用 group leader。
- group leader 数量由机器数决定：`floor(sqrt(num_agents))`。
- 分组目标是让同组机器位置尽量接近。
- 删除 `PULSE_GROUP_SIZE_LIMIT` 的硬限制，不再通过环境变量固定 group size 上限。

## 设计目标

- 降低 coordinator 写入压力：大集群由约 `sqrt(n)` 个 leader 聚合上报。
- 降低 agent 扰动：小集群不引入 leader/follower 协议复杂度。
- 保持位置局部性：同 group 优先来自同 area，同 area 内按 IPv6 地址排序，尽量把同机架或相近网段机器放在一起。
- 保持控制面简单：不新增 agent API，不要求静态部署脚本生成 group plan。
- 保持最终一致：coordinator 继续通过 heartbeat 动态维护 group plan，agent 收到新 plan 后自然切换。

## 分组域

coordinator 按 cluster 独立计算 group plan，不跨 cluster 分组。

每个 cluster 内的候选机器来自当前 host snapshot：

- `alive` 机器参与分组。
- 已有 group plan 的 `warming` 机器在 grace 期内保留，避免短暂心跳抖动导致频繁重排。
- `expired` 机器不参与分组。

如果 cluster 内候选机器数 `n < 5`：

- 不生成任何 group。
- 所有机器下发 `direct` plan。
- 不产生 leader。

如果 `n >= 5`：

- cluster 的目标 leader 数为 `floor(sqrt(n))`。
- 每个 leader 对应一个 group。
- group 数量不再受 `PULSE_GROUP_SIZE_LIMIT` 限制。

## 位置接近算法

### 排序键

位置接近使用稳定排序近似表达：

```text
cluster
  -> area
  -> IPv6 numeric order
  -> agent_id
```

规则：

- `area` 为空、`-` 或缺失时归一为 `unknown`。
- 优先不跨 area 建立 group。
- 同 area 内按 IP 地址的字节序排序；IPv6 地址天然包含网络位置和机架相邻性信息时，这个顺序能近似表达物理邻近。
- IP 无法解析时，回退到 `agent_id`，保证排序稳定。

### 分片策略

cluster 内先按 area 分桶，再在每个 area 内按 IPv6 排序。group 总数先由 cluster 机器数唯一决定：

```text
target_group_count = floor(sqrt(cluster_agents))
```

leader 数再按 area 规模分配：

- 如果 area 数量小于或等于 `target_group_count`，每个 area 至少分配 1 个 group。
- 剩余 group 按 `area_agents * target_group_count / cluster_agents` 的小数余量从大到小分配。
- 如果 area 数量大于 `target_group_count`，无法保证每个 area 独立成组，此时退化为全 cluster 的位置排序连续切片，只允许相邻 area 在边界处合并。
- 这使 cluster leader 总数严格等于 `floor(sqrt(cluster_agents))`，同时最大限度避免跨 area。

area 内分片使用连续切片：

```text
group_count = allocated_area_group_count
start = shard_index * area_agents / group_count
end = (shard_index + 1) * area_agents / group_count
members = sorted_area_agents[start:end]
```

连续切片的性质：

- 同组成员在排序序列中相邻。
- 排序序列先按 area 后按 IPv6，通常意味着同 group 机器在网络位置、机架或故障域上更接近。
- 分片大小自然均衡，差值最多为 1。

### 小 area 处理

小 area 不应为了凑 group 而随意跨 area 混组。

第一版策略：

- cluster 级别 `n < 5` 时整体 direct。
- cluster 级别 `n >= 5` 时，哪怕某个 area 自身少于 5 台，也可以作为该 area 的独立 group，前提是 `target_group_count` 足够覆盖所有 area。
- 当 area 数多于 `target_group_count` 时，只合并排序相邻的小 area，不跨越位置序列做远距离合并。
- 不使用字符串相似度猜测机架，只使用 `area + IPv6 numeric order` 作为第一版位置近似。

## Leader 选择

每个 group 的 leader 选择规则：

- 优先选择 group 内排序后的第一个 `alive` 成员。
- 如果 group 内没有 `alive`，选择 group 内第一个成员作为临时 leader。
- `leader_url` 使用 leader IP 和 `PULSE_GROUP_PORT` 生成。

leader 不是固定身份，coordinator 每次重算时根据存活状态和排序结果稳定选择。

## 删除 Size Limit

`PULSE_GROUP_SIZE_LIMIT` 删除后：

- coordinator 不再读取 `PULSE_GROUP_SIZE_LIMIT`。
- deploy 脚本不再写入 `PULSE_GROUP_SIZE_LIMIT`。
- agent dynamic 模式不再从环境变量读取默认 group size。
- leader 聚合 batch 不再使用固定 size limit 截断 members。
- coordinator 下发的 plan 可以保留 `size_limit` 字段作为兼容字段，但值必须等于当前 plan 的成员数，不代表配置上限。

兼容字段说明：

- `group_size`：当前 group 成员数。
- `group_size_limit` / `size_limit`：兼容旧 UI 和旧 plan payload；新语义为“本轮 plan 期望聚合成员数”。
- 新代码不得把它解释为 `PULSE_GROUP_SIZE_LIMIT` 或静态上限。

## Agent 行为

agent 收到 `direct` plan：

- 直接向 coordinator 发送 heartbeat。

agent 收到 `leader` plan：

- 接收当前 plan members 的 `/group/heartbeat`。
- 每轮 batch 只聚合当前 plan members。
- batch flush 条件中的 `BATCH_FULL` 使用当前 plan 成员数，而不是环境变量上限。

agent 收到 `follower` plan：

- 优先向 leader 的 `/group/heartbeat` 上报。
- leader 不可达或拒绝时 fallback direct，等待下一轮 coordinator plan。

## 示例

`cdn2` 有 50 台机器，分布在同一个 area：

```text
n = 50
group_count = floor(sqrt(50)) = 7
group sizes ~= 7, 7, 7, 7, 7, 7, 8
```

如果 `cdn2` 有两个 area：

```text
cluster_agents = 50
target_group_count = floor(sqrt(50)) = 7
area_a = 36 -> 5 groups
area_b = 14 -> 2 groups
```

两个 area 分别在自己的 IPv6 排序序列内连续切片，不跨 area 建组。

如果 cluster 有 3 个 area：

```text
cluster_agents = 50
target_group_count = 7
area_a = 40 -> 5 groups
area_b = 6 -> 1 group
area_c = 4 -> 1 group
```

虽然 `area_c` 自身少于 5 台，但 cluster 总数已经达到 group leader 门槛，且 leader 数足以覆盖该 area，因此保持 area 独立，不强行跨 area。

## 门禁

- 集群候选机器数小于 5 时不得产生 leader。
- group 数必须来自 cluster 级 `floor(sqrt(cluster_agents))`，不得由固定 group size 反推。
- group members 必须优先是同 area 内 IPv6 排序后的连续切片；当 area 数量多于 leader 数时，只允许位置序列相邻的 area 在边界处合并。
- 不得读取或写入 `PULSE_GROUP_SIZE_LIMIT`。
- leader batch 不得因为旧 size limit 截断当前 plan members。
- 测试必须覆盖：
  - 4 台不分组。
  - 16 台同 area 生成 4 个 group。
  - 50 台同 area 生成 7 个 group。
  - 多 area 不跨 area 建组。
  - IPv6 排序后连续切片。
