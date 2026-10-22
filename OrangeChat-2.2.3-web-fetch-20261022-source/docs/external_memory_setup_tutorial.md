# RikkaHub 外置记忆库（Supabase）完整配置教程

本教程面向希望把聊天记录、日记摘要保存到 Supabase，并让 AI 在对话时自动召回这些记忆的用户。

> 适用版本：RikkaHub 2.2.2 及之后（含本仓库当前修改）

---

## 目录

1. [前置条件](#1-前置条件)
2. [创建 Supabase 项目](#2-创建-supabase-项目)
3. [创建数据库表](#3-创建数据库表)
4. [配置 Row Level Security（RLS）](#4-配置-row-level-securityrls)
5. [在 App 里添加外置记忆库](#5-在-app-里添加外置记忆库)
6. [把记忆库绑定到助手](#6-把记忆库绑定到助手)
7. [配置 Embedding 模型（可选但强烈建议）](#7-配置-embedding-模型可选但强烈建议)
8. [测试记忆保存与召回](#8-测试记忆保存与召回)
9. [常见问题排查](#9-常见问题排查)
10. [代码修改说明（开发者）](#10-代码修改说明开发者)

---

## 1. 前置条件

- 一个可用的 Supabase 账号（免费版即可）
- RikkaHub App 已安装并能正常聊天
- 一个能生成 embedding 的模型（推荐 siliconflow 的 `BAAI/bge-m3` 或 `BAAI/bge-large-zh-v1.5`）

---

## 2. 创建 Supabase 项目

1. 打开 [https://app.supabase.com](https://app.supabase.com)
2. 点击 "New project"
3. 选择 Organization，填写：
   - **Name**：例如 `rikkahub-memory`
   - **Database Password**：设置一个强密码并保存好
4. 选择 Region（建议选离你近的，例如 `East Asia (N. Taiwan)` 或 `Southeast Asia (Singapore)`）
5. 点击 "Create new project"，等待创建完成（约 1-2 分钟）

---

## 3. 创建数据库表

项目创建好后，进入 SQL Editor，新建一个 Query，粘贴以下内容并执行：

```sql
-- 聊天记录表
CREATE TABLE IF NOT EXISTS public.chat_messages (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    assistant_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- 日记摘要 / 记忆向量表
CREATE TABLE IF NOT EXISTS public.memory_summaries (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    assistant_id TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    embedding VECTOR(1024)
);

-- 为常用查询创建索引
CREATE INDEX IF NOT EXISTS idx_chat_messages_assistant_created
    ON public.chat_messages (assistant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_memory_summaries_assistant_created
    ON public.memory_summaries (assistant_id, created_at DESC);
```

> 说明：
> - `chat_messages`：保存完整聊天记录
> - `memory_summaries`：保存日记摘要 / 长期记忆，并带向量用于语义搜索
> - `VECTOR(1024)` 假设你用的 embedding 模型输出 1024 维向量。如果你用其他维度（如 768、1536），请改成对应数字。

执行后，进入左侧 "Database" -> "Tables" 应该能看到这两个表。

---

## 4. 配置 Row Level Security（RLS）

为了保证数据安全，建议给这两张表开启 RLS，并允许匿名用户通过 API Key 读写：

```sql
-- chat_messages RLS
ALTER TABLE public.chat_messages ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow anon select chat_messages" ON public.chat_messages
    FOR SELECT USING (true);

CREATE POLICY "Allow anon insert chat_messages" ON public.chat_messages
    FOR INSERT WITH CHECK (true);

-- memory_summaries RLS
ALTER TABLE public.memory_summaries ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow anon select memory_summaries" ON public.memory_summaries
    FOR SELECT USING (true);

CREATE POLICY "Allow anon insert memory_summaries" ON public.memory_summaries
    FOR INSERT WITH CHECK (true);
```

> 注意：这里是为了让 App 用 `anon` 角色访问。如果你的 Key 是 `service_role`，则不需要 RLS。建议生产环境使用更严格的策略。

---

## 5. 在 App 里添加外置记忆库

1. 打开 RikkaHub App
2. 进入 **设置 -> 外置记忆库**（或"进阶记忆"）
3. 点击右上角 "+" 添加新记忆库
4. 填写以下信息：

| 字段 | 说明 | 示例 |
|------|------|------|
| 名称 | 记忆库显示名称 | `我的记忆库` |
| Supabase URL | 项目 URL | `https://xxxxx.supabase.co` |
| Supabase Key | 项目 API Key | `eyJhbG...` |
| 聊天记录表名 | 保存聊天记录的表 | `chat_messages` |
| 日记摘要表名 | 保存日记摘要的表 | `memory_summaries` |
| 自动保存聊天记录 | 是否自动保存每条对话 | 开启 |
| 自动保存日记摘要 | 是否生成并保存日记摘要 | 开启 |
| 召回数量 | 每次召回多少条 | `5` |
| Embedding 模型 | 用于生成向量的模型 | 选择你配置的 embedding 模型 |

5. 点击保存

### 如何获取 Supabase URL 和 Key

1. 在 Supabase 项目左侧点击 "Project Settings"（齿轮图标）
2. 选择 "API"
3. 复制：
   - **Project URL**：就是 Supabase URL
   - **anon public** 或 **service_role** 的 API Key：就是 Supabase Key

> 建议：开发测试可以用 `anon public`。如果后续需要 Edge Function 写数据，可以用 `service_role`。

---

## 6. 把记忆库绑定到助手

1. 打开 RikkaHub App
2. 进入 **设置 -> 助手 -> 选择一个助手**
3. 找到 **外置记忆库** 选项
4. 勾选你刚才添加的记忆库
5. 返回并保存助手设置

> 只有绑定了记忆库的助手，才会在聊天时自动保存和召回记忆。

---

## 7. 配置 Embedding 模型（可选但强烈建议）

如果你想用向量召回日记摘要（而不是简单的关键词搜索），需要在 App 里配置一个 embedding 模型：

1. 进入 **设置 -> 模型提供方**
2. 添加一个 Provider（例如 siliconflow）
3. 在该 Provider 下添加一个 Embedding 模型：
   - 模型 ID：`BAAI/bge-m3`（推荐）或 `BAAI/bge-large-zh-v1.5`
   - 类型选择 Embedding
   - 填写 API Key
4. 回到外置记忆库配置，在 "Embedding 模型" 字段选择刚才添加的模型

> 如果 Embedding 模型配置不正确，App 会回退到文本搜索 `chat_messages` 表，召回质量会差很多。

---

## 8. 测试记忆保存与召回

### 8.1 测试保存

1. 确保当前助手已绑定外置记忆库
2. 发送几条消息和 AI 聊天
3. 过一段时间后，去 Supabase 的 SQL Editor 执行：

```sql
SELECT * FROM public.chat_messages ORDER BY created_at DESC LIMIT 10;
```

应该能看到刚保存的聊天记录。

### 8.2 测试日记摘要

日记摘要通常由 Edge Function 或 App 内部定时任务生成。如果你是手动测试，可以直接插入一条：

```sql
INSERT INTO public.memory_summaries (assistant_id, content, embedding)
VALUES (
    '你的助手ID',
    '用户喜欢在下雨天听周杰伦的歌，特别喜欢吃抹茶口味的东西。',
    NULL
);
```

然后回到 App，问 AI："我喜欢吃什么？" 或 "下雨天我喜欢做什么？"

如果配置了 embedding，AI 应该能召回这条记忆。

### 8.3 测试向量召回

如果你配置了 embedding，可以在 SQL Editor 手动验证：

```sql
SELECT content, embedding <=> '[你的查询向量]' AS distance
FROM public.memory_summaries
WHERE assistant_id = '你的助手ID'
ORDER BY distance
LIMIT 5;
```

---

## 9. 常见问题排查

### 9.1 AI 说"找不到记忆"

1. 检查当前助手是否绑定了外置记忆库
2. 检查外置记忆库是否启用
3. 检查 Supabase URL 和 Key 是否填对
4. 检查表名是否填对（默认 `chat_messages` 和 `memory_summaries`）
5. 抓 logcat 搜索 `ExternalMemory config`，看实际使用的配置

### 9.2 AI 召回的是旧账号/旧数据库的数据

这通常是因为 AI 的 `supabase_query` 工具之前读取的是"系统工具 -> Supabase 数据同步"里的旧配置。

修复方式：
- 确保外置记忆库配置正确
- 安装本仓库最新编译的 APK（已修复该问题）
- 如果还用了 MCP server，检查 MCP server 配置是否也指向旧数据库

### 9.3 Embedding 请求报 401

1. 检查 embedding 模型的 API Key 是否正确
2. 检查该模型是否在你的 Provider 里正确配置
3. 检查 Provider 的 base URL 是否正确

### 9.4 `memory_summaries` 表不存在

如果报错 `Could not find the table 'public.memory_summaries'`，说明没有执行第 3 步的建表 SQL。

### 9.5 向量维度不匹配

如果插入 embedding 时报错 `expected 1024 dimensions, got X`，说明：
- 你的 embedding 模型输出维度不是 1024
- 需要修改 `memory_summaries` 表的 `VECTOR(1024)` 为对应维度

---

## 10. 代码修改说明（开发者）

本次修复涉及以下文件：

### 10.1 `MemoryRepository.kt`

本地记忆召回时过滤掉日记摘要类型，避免旧的本地日记摘要混入 AI 的 system prompt：

```kotlin
private fun MemoryEntity.isSummaryMemory(): Boolean {
    return content.startsWith("[daily_summary]") ||
        content.startsWith("[phase_summary]") ||
        content.startsWith("[auto_summary]")
}
```

### 10.2 `GenerationHandler.kt`

外置记忆库召回前增加日志，打印实际使用的 URL、表名、embedding 模型等信息，便于排查：

```kotlin
externalMemoryConfigs.forEach { config ->
    Log.i(TAG, "ExternalMemory config: name=${config.name}, url=${config.supabaseUrl}, ...")
}
```

### 10.3 `SystemTools.kt`

`supabase_query` 工具不再从 `SystemToolsSetting` 读取旧配置，而是从 `settings.externalMemories` 读取外置记忆库配置：

```kotlin
val externalMemories = settings.externalMemories.filter { it.enabled }
val memory = externalMemories.firstOrNull { it.tableName == table || it.summariesTableName == table }
    ?: externalMemories.first()
val baseUrl = memory.supabaseUrl.trimEnd('/')
val apiKey = memory.supabaseKey
```

### 10.4 `SystemToolsSetting.kt`

移除 `supabaseEnabled/supabaseUrl/supabaseApiKey` 对 `SupabaseQuery` 工具开关的控制：

```kotlin
// SupabaseQuery 现在由外置记忆库配置驱动
```

### 10.5 `ChatService.kt`

当存在启用的外置记忆库时，自动把 `SupabaseQuery` 加入系统工具列表：

```kotlin
val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions().toMutableSet()
if (settings.externalMemories.any { it.enabled }) {
    systemToolsOptions.add(me.rerere.rikkahub.data.ai.tools.SystemToolOption.SupabaseQuery)
}
```

---

## 附录：完整 SQL 脚本

```sql
-- 建表
CREATE TABLE IF NOT EXISTS public.chat_messages (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    assistant_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.memory_summaries (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    assistant_id TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    embedding VECTOR(1024)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_chat_messages_assistant_created
    ON public.chat_messages (assistant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_memory_summaries_assistant_created
    ON public.memory_summaries (assistant_id, created_at DESC);

-- RLS
ALTER TABLE public.chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.memory_summaries ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow anon select chat_messages" ON public.chat_messages FOR SELECT USING (true);
CREATE POLICY "Allow anon insert chat_messages" ON public.chat_messages FOR INSERT WITH CHECK (true);
CREATE POLICY "Allow anon select memory_summaries" ON public.memory_summaries FOR SELECT USING (true);
CREATE POLICY "Allow anon insert memory_summaries" ON public.memory_summaries FOR INSERT WITH CHECK (true);
```

---

如果你按本教程配置后仍有问题，请提供以下信息：
1. 当前助手绑定的外置记忆库名称
2. Supabase URL（可以隐藏部分）
3. 表名
4. 是否配置了 embedding 模型
5. logcat 中搜索 `ExternalMemory` 和 `supabase_query` 的相关日志
