-- ============================================================
-- 外置记忆库（进阶记忆）Supabase 建表 SQL
-- App 默认配置：
--   tableName         = 'chat_messages'
--   summariesTableName = 'memory_summaries'
-- ============================================================

-- 1. 启用 pgvector 扩展（用于日记向量检索）
create extension if not exists vector;

-- ============================================================
-- 2. 聊天消息表 chat_messages
-- ============================================================
create table if not exists public.chat_messages (
    id bigserial primary key,
    assistant_id text not null,
    conversation_id text,
    role text not null,
    content text,
    created_at timestamp not null default now()
);

comment on table public.chat_messages is '外置记忆库 - 聊天消息';

-- RLS：允许带 apikey 的匿名角色读写（App 用 supabaseKey 直连）
alter table public.chat_messages enable row level security;

drop policy if exists "allow all for anon" on public.chat_messages;
create policy "allow all for anon"
    on public.chat_messages
    for all
    to anon, authenticated
    using (true)
    with check (true);

-- 索引
 create index if not exists chat_messages_created_at_idx
    on public.chat_messages (created_at);
create index if not exists chat_messages_assistant_idx
    on public.chat_messages (assistant_id);

-- ============================================================
-- 3. 日记摘要表 memory_summaries
-- ============================================================
-- 注意：
--   embedding 维度必须和你配置的 embedding 模型一致。
--   如果不确定维度，可以先不创建 embedding 列，App 保存失败会自动降级为不带向量保存。
--   常用维度参考：
--     - SiliconFlow bge-m3           = 1024
--     - OpenAI text-embedding-3-small = 1536
--     - OpenAI text-embedding-3-large = 3072
-- ============================================================

create table if not exists public.memory_summaries (
    id bigserial primary key,
    assistant_id text not null,
    content text,
    created_at timestamp not null default now(),
    -- 如不需要向量检索，可将下面这行删除
    embedding vector(1024)
);

comment on table public.memory_summaries is '外置记忆库 - 日记摘要';

alter table public.memory_summaries enable row level security;

drop policy if exists "allow all for anon" on public.memory_summaries;
create policy "allow all for anon"
    on public.memory_summaries
    for all
    to anon, authenticated
    using (true)
    with check (true);

-- 向量检索索引（ivfflat 余弦相似度），不需要向量检索可跳过
 create index if not exists memory_summaries_embedding_idx
    on public.memory_summaries
    using ivfflat (embedding vector_cosine_ops)
    with (lists = 100);

create index if not exists memory_summaries_assistant_created_idx
    on public.memory_summaries (assistant_id, created_at);

-- ============================================================
-- 4. 刷新 PostgREST schema 缓存（可选）
-- ============================================================
-- Supabase 通常会自动刷新，若发现 API 无法识别新列可手动执行：
-- notify pgrst, 'reload schema';
