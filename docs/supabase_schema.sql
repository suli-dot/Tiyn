-- Схема Supabase (PostgreSQL) для синхронизации.
-- Выполнить в Supabase → SQL Editor. RLS включён на обеих таблицах:
-- пользователь видит и пишет только свои строки.

-- ============ raw_notifications ============
-- Первичный ключ — составной (user_id, client_id): client_id это локальный id из Room.
-- upsert без on_conflict мёржит именно по PK, поэтому повторная выгрузка не плодит дубли.
create table if not exists public.raw_notifications (
    user_id       uuid not null default auth.uid() references auth.users (id) on delete cascade,
    client_id     bigint not null,        -- локальный id из Room
    package_name  text not null,
    title         text,
    text          text not null,
    posted_at     timestamptz not null,
    created_at    timestamptz not null default now(),
    primary key (user_id, client_id)
);

alter table public.raw_notifications enable row level security;

create policy "raw_select_own" on public.raw_notifications
    for select using (auth.uid() = user_id);
create policy "raw_insert_own" on public.raw_notifications
    for insert with check (auth.uid() = user_id);
create policy "raw_update_own" on public.raw_notifications
    for update using (auth.uid() = user_id);
create policy "raw_delete_own" on public.raw_notifications
    for delete using (auth.uid() = user_id);

-- ============ transactions ============
-- PK составной (user_id, client_id) — см. пояснение выше.
create table if not exists public.transactions (
    user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
    client_id   bigint not null,          -- локальный id из Room
    raw_id      bigint,                   -- локальный client_id связанного raw_notifications (без FK: облако = архив)
    amount      bigint not null,          -- ТИЫНЫ (1 тг = 100), integer
    type        text not null check (type in ('PURCHASE','TRANSFER','INCOME')),
    merchant    text,
    category    text,
    currency    text not null default 'KZT',
    created_at  timestamptz not null,
    edited_at   timestamptz,              -- время ручной правки (null = запись не редактировалась)
    deleted_at  timestamptz,              -- soft delete (null = запись активна)
    synced_at   timestamptz not null default now(),
    primary key (user_id, client_id)
);

alter table public.transactions enable row level security;

create policy "tx_select_own" on public.transactions
    for select using (auth.uid() = user_id);
create policy "tx_insert_own" on public.transactions
    for insert with check (auth.uid() = user_id);
create policy "tx_update_own" on public.transactions
    for update using (auth.uid() = user_id);
create policy "tx_delete_own" on public.transactions
    for delete using (auth.uid() = user_id);

create index if not exists idx_tx_user_created on public.transactions (user_id, created_at desc);
