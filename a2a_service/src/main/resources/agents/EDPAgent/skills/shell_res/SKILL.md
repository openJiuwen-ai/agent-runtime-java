---
name: shell_res_skill
description: >
  执行 Shell 资源脚本并获取输出结果。
  触发词：执行脚本、运行脚本、shell命令。
  不要用于：理财推荐、账户查询、转账。
---

# Shell 资源 Skill

## 职责

执行指定 Shell 脚本，获取脚本输出结果并返回给用户。

## 工具白名单（严格）

只允许调用以下工具：
- `executeCmd`

禁止调用 `ask_user`、`call_versatile` 或其他非白名单工具。

## 执行流程

### 第一步：确认需要执行脚本（仅思考，不调工具）

从用户输入中识别需要执行 Shell 脚本的意图。

### 第二步：调用 `executeCmd` 执行脚本

```
executeCmd(
  command="bash shell_res/shell_res.sh",
  cwd="skills",
  timeout=30
)
```

参数说明：
- `command`：要执行的 Shell 命令，使用 `bash` 执行 `shell_res/shell_res.sh`
- `cwd`：工作目录，固定为 `"skills"`
- `timeout`：超时秒数，默认 30

### 第三步：处理返回结果

`executeCmd` 返回结构：
```json
{
  "code": 0,
  "data": {
    "command": "bash shell_res/shell_res.sh",
    "cwd": "skills",
    "exitCode": 0,
    "stdout": "Hello Shell",
    "stderr": ""
  }
}
```

- 若 `exitCode != 0`：回复脚本执行失败，附上 `stderr` 内容。
- 若 `exitCode == 0`：将 `stdout` 内容直接返回给用户。

## 约束

- 不要自行编造输出内容，只展示脚本真实返回的 stdout。
- 每次只调用一次 `executeCmd`。
- 脚本路径使用相对路径 `shell_res/shell_res.sh`，cwd 固定为 `"skills"`。