# ZeroBot Chat Summary

群聊总结报告插件。插件会在运行时缓存群消息，收到总结命令后生成一张长图报告并回复到群里。

报告内容包括：

- 群聊概况和统计卡片
- 参与成员列表
- 24 小时活跃分布
- 活跃用户排行
- 热词词云
- 群奖项、互动关系、主要话题
- 群友画像、群聊金句

## 使用方式

把插件 JAR 放入 ZeroBot 运行目录的 `plugins` 文件夹，然后在 ZeroBot 控制台执行：

```text
reload-all
```

群内发送以下任一命令：

```text
/群总结
/群聊总结
/summary
```

命令参数：

```text
/群总结          # 默认总结最近 24 小时
/群总结 6        # 总结最近 6 小时
/群总结 2天      # 总结最近 48 小时
/群总结 今日     # 总结今天 00:00 到现在
/群总结 昨天     # 总结昨天 00:00 到今天 00:00
```

插件会把群聊上下文追加保存为 JSONL。刚安装后立刻生成报告时，如果还没有记录到新消息，会提示暂无可用记录。

## 构建

本项目依赖 `cn.zerobot:zerobot-plugin-api:0.1.5`，该 API 使用 Java 21 编译。请使用 JDK 21 构建：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat jar
```

生成的插件：

```text
build\libs\zerobot-chat-summary-1.0.0.jar
```

## 配置

首次加载后，ZeroBot 会生成：

```text
config/chat-summary/config.yml
```

常用配置项：

```yml
reportPermission: chat-summary.report
reportDefaultAllowed: true
defaultHours: 24
maxHours: 168
retentionHours: 168
maxMessagesPerGroup: 6000
storageEnabled: true
storageRetentionDays: 14
cleanupIntervalMinutes: 60
reportWidth: 560
renderScale: 2
sendGeneratingReply: false
timeZone: Asia/Shanghai
aiEnabled: false
aiBaseUrl: https://api.openai.com/v1
aiApiKey: ""
aiApiKeyEnv: OPENAI_API_KEY
aiModel: gpt-5.4-nano
aiTimeoutSeconds: 120
aiMaxMessages: 360
aiMaxChars: 30000
aiMaxOutputTokens: 2200
```

新版 ZeroBot 的命令入口声明在 `plugin.yml` 的 `commands` 中。

生成的报告图片会保存在：

```text
data/chat-summary/reports/
```

群消息上下文会保存为一行一个 JSON：

```text
data/chat-summary/messages/<群号>/<yyyy-MM-dd>.jsonl
```

`storageRetentionDays` 控制 JSONL 历史保留天数。插件会按 `cleanupIntervalMinutes` 定期清理过期 JSONL 文件和内存里的过期群聊缓存。内存缓存仍会保留，用于快速读取最近消息和磁盘不可用时兜底。

## AI 报告策划

默认不开启 AI，插件会使用本地规则生成报告。开启后，AI 会根据聊天上下文决定摘要、热词、群奖项、主要话题、群友画像和金句，插件负责把 AI 的报告方案渲染成清晰长图。

```yml
aiEnabled: true
aiApiKey: sk-...
```

也可以不把 key 写进配置，改用环境变量：

```powershell
$env:OPENAI_API_KEY='sk-...'
```

`aiBaseUrl` 使用 OpenAI Chat Completions 兼容接口，默认请求 `https://api.openai.com/v1/chat/completions`。如果你用 OneAPI、DeepSeek 兼容网关或自建代理，可以改成对应的 `/v1` 地址，并把 `aiModel` 改成服务商支持的模型。

消息数、参与人数、活跃时段等统计数据仍由插件本地计算，避免 AI 改写事实。接口超时、返回格式异常或未配置 key 时，会自动回退到本地规则版报告。

## 权限

默认权限节点：

```text
chat-summary.report
```

`reportDefaultAllowed: true` 表示没有额外权限插件接管时，所有群成员默认可用。需要只允许管理员或指定用户使用时，可以把它改为 `false`，再由权限插件分配该节点。
