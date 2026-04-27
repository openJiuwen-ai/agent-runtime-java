---
name: rebuild_interact_finance_rec_skill
description: >
  处理首次推荐完成后的后续交互式多轮理财产品推荐，以及首次推荐的兜底场景。
  触发词：换一批、再推荐、换个稳健型、不要R5、换个短期理财、换个收益高的、追加条件、修改筛选条件。
  兜底场景触发：当 rebuild_product_recommend_skill 未能提供推荐时的首次推荐请求。
  不要用于：产品选择确认、资金筹划、账户查询。
---

# 交互式多轮理财推荐 Skill

## 职责

1. **后续交互推荐**：用户在首次推荐完成后，请求换一批、追加筛选条件、修改筛选条件等
2. **兜底首次推荐**：当 `rebuild_product_recommend_skill` 未能提供推荐时，作为兜底提供首次推荐

MCP 先行架构：先通过 `call_mcp` 获取 MCP 产品推荐，再通过 `call_versatile` 获取低码平台的 bankCardNumber，最后在沙箱中合并归一化结果。

## 工具白名单（严格）

只允许调用以下工具（按执行顺序排列）：
- `call_mcp`（第一步：MCP 产品推荐）
- `call_versatile`（第二步：低码平台交互）

禁止调用 `ask_user` 或其他非白名单工具。

## 固定参数

### call_mcp 固定参数
- 无固定值参数，所有参数由上下文状态和用户偏好决定

### call_versatile 固定参数
- `query_intent`：`"理财推荐"`（与 `rebuild_product_recommend_skill` 相同，路由到同一个 VersatileAdapter 低码工作流，区别在于沙箱归一化脚本不同）
- `query_response_analysis_scripts`：`"python rebuild_interact_finance_rec_skill/scripts/run_interact_finance_rec_skill.py"`

## 环境变量规则

MCP 环境变量（MCP_JD_URL、MCP_XSQ_URL、MCP_ACCESS_TOKEN、MCP_APP_NAME）由系统自动从 .env 注入，LLM 不需要也不允许在 skill_context 中传递 env_vars。

## MCP 参数映射规则

从用户输入中提取偏好，映射为 MCP 筛选参数：

| 用户表达 | MCP 参数 | 映射值 |
|----------|----------|--------|
| 保守/稳健型/进取型 | filterRiskLevel | R1→1, R2→2, R3→3, R4→4, R5→5 |
| 短期/中期/长期 | filterTerm | 短期→1, 中期→2, 长期→3 |
| 固定收益/权益/混合 | filterProductType | 固定收益→01, 权益→02, 混合→03 |
| 收益高/稳健/期限优先 | sortType | 收益→1, 稳健→2, 期限→3 |
| 最低收益 X% | filterMinYield | 数值（如 3.0） |
| 最高收益 X% | filterMaxYield | 数值（如 5.0） |
| 最低金额 X万 | filterMinAmount | 数值（如 10000） |
| 最高金额 X万 | filterMaxAmount | 数值（如 50000） |
| 某银行的产品 | filterOrgCode | 银行编码 |
| 关键词搜索 | filterKeyword | 文本 |
| 货币类型 | filterCurrency | CNY/USD 等 |

## 执行流程

### 第一步：提取用户偏好（仅思考，不调工具）

从用户输入中识别偏好变化：
- 新增筛选条件：用户说"换个稳健型的" → filterRiskLevel="2"
- 修改筛选条件：用户说"不要R5" → filterRiskLevel 不包含 5
- 换一批：用户说"换一批" → 不修改筛选条件，sortType 自动轮换

### 第二步：调用 `call_mcp` 获取 MCP 产品推荐

```
call_mcp(
  mcp_params='{"filterRiskLevel": "2"}',
  mcp_required_params='{"clientIP": "...", "userAgent": "...", "wapSessionId": "...", "wapbCookieList": "...", "wap_grayFlag": "..."}',
  history_product_codes='["250761", "250762"]',
  current_sort_type=0,
  history_recommend_params='{"filterRiskLevel": "2", "sortType": "0"}',
  skill_context='{"is_first_recommend": false}'
)
```

参数说明：
- `mcp_params`（必填）：JSON 字符串，本轮新增/修改的筛选参数
- `mcp_required_params`（必填）：JSON 字符串，MCP 必输参数（从上下文获取，每轮保持不变）
- `history_product_codes`（选填）：JSON 字符串，历史已推荐产品编码列表（从上一轮返回的 history_product_codes 获取）
- `current_sort_type`（选填）：当前 sortType 值（从上一轮返回的 next_sort_type 获取）
- `history_recommend_params`（选填）：JSON 字符串，历史推荐参数（从上一轮返回的 updated_recommend_params 获取）
- `skill_context`（选填）：JSON 字符串，额外上下文信息

call_mcp 由 MCPInterruptRail 拦截并执行沙箱脚本，返回结构：
```json
{
  "products": [{"productCode": "250871", "productName": "产品B"}],
  "total": 1,
  "next_sort_type": 1,
  "updated_recommend_params": {"filterRiskLevel": "2", "sortType": "1"},
  "history_product_codes": ["250761", "250871"],
  "mcp_error": null
}
```

**call_mcp 返回后，必须将以下状态字段保存到上下文中**：
- `next_sort_type` → 下一轮的 `current_sort_type`
- `updated_recommend_params` → 下一轮的 `history_recommend_params`
- `history_product_codes` → 下一轮的 `history_product_codes`

### 第三步：调用 `call_versatile` 获取低码平台 bankCardNumber

```
call_versatile(
  query_description="换一批理财产品，风险等级：R2，排序：近1月年化",
  query_intent="理财推荐",
  query_response_analysis_scripts="python rebuild_interact_finance_rec_skill/scripts/run_interact_finance_rec_skill.py",
  skill_context='{"mcp_params": {"filterRiskLevel": "2"}, "mcp_required_params": {"clientIP": "...", "userAgent": "...", "wapSessionId": "...", "wapbCookieList": "...", "wap_grayFlag": "..."}, "history_product_codes": ["250761", "250762"], "current_sort_type": 0, "history_recommend_params": {"filterRiskLevel": "2", "sortType": "0"}}'
)
```

参数说明：
- `query_description`：自然语言查询，根据用户偏好拼装。如无偏好变化，使用 `"换一批理财产品"`。
- `query_intent`：固定为 `"理财推荐"`
- `query_response_analysis_scripts`：固定为 `"python rebuild_interact_finance_rec_skill/scripts/run_interact_finance_rec_skill.py"`
- `skill_context`：JSON 字符串，包含以下字段：
  - `mcp_params`：本轮新增/修改的筛选参数
  - `mcp_required_params`：MCP 必输参数（从上下文获取，每轮保持不变）
  - `history_product_codes`：历史已推荐产品编码列表（从上一轮返回的 history_product_codes 获取）
  - `current_sort_type`：当前 sortType 值（从上一轮返回的 next_sort_type 获取）
  - `history_recommend_params`：历史推荐参数（从上一轮返回的 updated_recommend_params 获取）

**注意**：call_versatile 的 skill_context 中不再包含 `env_vars`（MCP 环境变量由系统自动从 .env 注入）。

VersatileInterruptRail 会自动从 session state 读取 MCP 产品数据并注入到低码平台的 delegate 请求中（productListJsonData），LLM 不需要处理。

### 第四步：处理合并归一化结果

call_versatile 返回结构（沙箱归一化后）：
```json
{
  "products": [
    {
      "productCode": "250871",
      "productName": "工银理财「稳利」净值型理财产品",
      "productType": "固定收益类",
      "profitValue": "3.2%",
      "riskLevel": "R2"
    }
  ],
  "bankCardNumber": "6605",
  "total": 3,
  "next_sort_type": 1,
  "updated_recommend_params": {"filterRiskLevel": "2", "sortType": "1"},
  "history_product_codes": ["250761", "250762", "250871"]
}
```

**若返回包含 error 字段（mcp_timeout）**：
```
暂时无法获取理财产品信息，请稍后再试。
```
结束，不需要调用其他工具。

**若返回包含 error 字段（no_products，兜底场景）**：
```
没有符合您要求的理财产品，请重新描述需求。
```
结束，不需要调用其他工具。

**若 products 为空（total == 0）且无 error 字段**：
```
暂无符合您条件的理财产品，请调整筛选条件后重试。
```
结束，不需要调用其他工具。

**若 products 不为空但 bankCardNumber 为空**：
```
已查询到 {total} 款理财产品，但未获取到您的理财卡信息，不符合购买要求。
请先绑定理财卡后再尝试。
```
结束，不需要调用其他工具。

**若 products 不为空且 bankCardNumber 不为空**：
按下方格式展示产品列表（最多展示前 5 条）：

---
为您推荐以下理财产品：

| 序号 | 产品名称 | 产品类型 | 预期年化收益 | 风险等级 |
|------|----------|----------|-------------|----------|
| 1 | {productName} | {productType} | {profitValue} | {riskLevel} |
| 2 | ... | ... | ... ... | ... |

您的理财卡尾号：{bankCardNumber}

如需购买，请告诉我您想选择哪款产品及购买金额。
如需换一批或调整筛选条件，请直接告诉我。
---

**重要**：处理返回结果后，必须将以下状态字段保存到上下文中，供下一轮调用使用：
- `next_sort_type` → 下一轮的 `current_sort_type`
- `updated_recommend_params` → 下一轮的 `history_recommend_params`
- `history_product_codes` → 下一轮的 `history_product_codes`

## 兜底场景说明

本 Skill 可作为首次推荐的兜底：当 `rebuild_product_recommend_skill` 未能提供推荐时，本 Skill 接管首次推荐请求。

兜底场景下参数默认值：
- `history_product_codes`：`[]`（空列表）
- `current_sort_type`：`0`
- `history_recommend_params`：`{}`（空对象）
- `is_first_recommend`：`true`（系统自动推断，无需 LLM 显式传入）

兜底场景输出策略：
- 0 个产品 → 提示"没有符合您要求的理财产品，请重新描述需求"
- 1 个产品 → 展示 1 个
- 2 个及以上 → 只展示前 2 个（引导用户继续交互）

兜底场景仍按完整四步流程执行（call_mcp → call_versatile → 处理结果），无需特殊分支。

## 字段说明

| 字段 | 说明 |
|------|------|
| productCode | 产品代码（内部唯一标识，选品时需要） |
| productName | 产品全名 |
| productType | 产品类型：固定收益类 / 混合类 / 权益类 |
| profitValue | 预期年化收益率，如 3.2% |
| riskLevel | 风险等级：R1（最低）~ R5（最高） |
| bankCardNumber | 用户理财卡后四位，如 6605 |
| next_sort_type | 下一轮 sortType 值（自动轮换） |
| updated_recommend_params | 下一轮历史推荐参数（增量覆盖基础） |
| history_product_codes | 已推荐产品编码列表（用于去重） |
| mcp_error | MCP 调用错误信息（沙箱脚本字段，call_versatile 归一化后不暴露给 LLM） |

## 约束

- 禁止自行编造产品信息，只展示工具返回的真实数据。
- 推荐列表超过 5 条时截取前 5 条，末尾加提示"（共 {total} 个产品，已为您展示前 5 条）"。
- 不要替用户做出选择，那是 `rebuild_product_select_skill` 的职责。
- 每次执行按顺序调用一次 `call_mcp` 和一次 `call_versatile`，不重复调用。
- 最多 10 轮推荐，超过后主动终止并告知用户"已为您推荐多轮产品，如需继续请重新开始推荐"。
- MCP 环境变量由系统管理，禁止在 skill_context 中传递 env_vars。
