# com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext

## class AgentLifecycleContext

```java
public final class AgentLifecycleContext
```

生命周期 hook 上下文，携带应用名和可扩展属性。

## 构造方法

| Signature | Description |
| --- | --- |
| `public AgentLifecycleContext(String appName)` | 使用空 attributes 创建上下文。 |
| `public AgentLifecycleContext(String appName, Map<String, Object> attributes)` | 使用指定 attributes 创建上下文。 |

## 方法

| Signature | Description |
| --- | --- |
| `public String getAppName()` | 返回运行中的 Agent Service 应用名。 |
| `public Map<String, Object> getAttributes()` | 返回只读 attributes。 |
| `public void setAttribute(String key, Object value)` | 写入扩展属性。 |
| `public <T> T getAttribute(String key)` | 读取扩展属性并转换为目标类型。 |
