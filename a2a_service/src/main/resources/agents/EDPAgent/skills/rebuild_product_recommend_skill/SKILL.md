---
name: rebuild_product_recommend_skill
description: >
  根据用户理财意图进入理财推荐流程并展示推荐产品列表。
  触发词：推荐理财产品、帮我看看理财、有什么理财可以买。
  不要用于：产品选择确认、资金筹划、账户查询。
---

# 产品推荐 Skill

## 职责

接收用户的理财购买意向，通过 `call_versatile` 触发理财推荐工作流，
获取推荐产品列表，按清晰格式展示给用户，并告知用户可进入选品流程。

## 工具白名单（严格）

只允许调用以下工具：
- `call_versatile`

禁止调用 `ask_user` 或其他非白名单工具。

## 固定参数

本 Skill 所有 `call_versatile` 调用的固定参数：
- `query_intent`：`"理财推荐"`
- `query_response_analysis_scripts`：`"python rebuild_product_recommend_skill/scripts/run_product_recommend_skill.py"`

## 执行流程

### 第一步：提取用户偏好（仅思考，不调工具）

从用户输入中识别：
- 风险偏好：保守 → R1/R2，稳健 → R2/R3，进取 → R3/R4
- 产品类型：固收类 / 混合类 / 权益类
- 如无明确偏好，不传任何过滤参数

### 第二步：调用 `call_versatile` 触发推荐工作流

```
call_versatile(
  query_description="推荐理财产品，关键词：固收，风险等级：R2",
  query_intent="理财推荐",
  query_response_analysis_scripts="python rebuild_product_recommend_skill/scripts/run_product_recommend_skill.py"
)
```

### 第三步：处理返回结果

- 若 `products` 为空：回复暂无符合条件的理财产品。
- 若 `products` 不为空但 `bankCardNumber` 为空：提示先绑定理财卡。
- 若 `products` 不为空且 `bankCardNumber` 不为空：展示前 5 条结果，并引导用户进入选品流程。

## 约束

- 禁止自行编造产品信息，只展示 `call_versatile` 返回的真实数据。
- 推荐列表超过 5 条时截取前 5 条。
- 不要替用户做出选择，那是 `rebuild_product_select_skill` 的职责。
- 每次执行只调用一次 `call_versatile`，不重复调用。
