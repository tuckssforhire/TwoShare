-- TwoShare 1.0.38: Shared Lists + live chat/call/list updates
--
-- Run once in Supabase -> SQL Editor. Safe to run again: every step is idempotent and the
-- whole script runs in one transaction, so a failure leaves the database unchanged.
-- Requires public.profiles and public.is_twoshare_user() from supabase/setup.sql.
-- Does not modify any existing table except adding chat/call tables to the Realtime
-- publication (which does not change their data, policies or RLS).

begin;

do $$
begin
  if to_regprocedure('public.is_twoshare_user()') is null then
    raise exception 'public.is_twoshare_user() is missing. Run supabase/setup.sql first.';
  end if;
end $$;

-- 1. Tables -------------------------------------------------------------------
create table if not exists public.shared_lists (
  id uuid primary key default gen_random_uuid(),
  title text not null check (char_length(btrim(title)) between 1 and 60),
  emoji text not null default '📝' check (char_length(emoji) between 1 and 16),
  created_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now()
);

create table if not exists public.shared_list_items (
  id uuid primary key default gen_random_uuid(),
  list_id uuid not null references public.shared_lists(id) on delete cascade,
  text text not null check (char_length(btrim(text)) between 1 and 200),
  done boolean not null default false,
  added_by uuid references public.profiles(id) on delete set null,
  done_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  done_at timestamptz
);

create index if not exists shared_list_items_list_idx on public.shared_list_items (list_id, created_at);

-- Internal flags (e.g. "starter lists already created"). RLS on with no policies and no
-- grants, so the app cannot read or change it.
create table if not exists public.twoshare_app_state (
  key text primary key,
  value jsonb not null default 'true'::jsonb,
  updated_at timestamptz not null default now()
);
alter table public.twoshare_app_state enable row level security;
revoke all on table public.twoshare_app_state from anon, authenticated;

-- 2. Server-owned attribution ---------------------------------------------------------
-- The database, not the app, decides who created a list, who added an item and who ticked
-- it off. Values sent by a client for these columns are ignored. Rows inserted without a
-- signed-in user (SQL editor, service role) keep whatever the statement provides.
create or replace function public.twoshare_lists_attribution()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if tg_op = 'INSERT' then
    if auth.uid() is not null then new.created_by := auth.uid(); end if;
    new.created_at := now();
  else
    new.id := old.id;
    new.created_by := old.created_by;
    new.created_at := old.created_at;
  end if;
  return new;
end $$;

create or replace function public.twoshare_list_items_attribution()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  if tg_op = 'INSERT' then
    if auth.uid() is not null then new.added_by := auth.uid(); end if;
    new.created_at := now();
    if new.done then
      new.done_by := coalesce(auth.uid(), new.done_by);
      new.done_at := now();
    else
      new.done_by := null;
      new.done_at := null;
    end if;
  else
    new.id := old.id;
    new.list_id := old.list_id;
    new.added_by := old.added_by;
    new.created_at := old.created_at;
    if new.done is distinct from old.done then
      if new.done then
        new.done_by := coalesce(auth.uid(), new.done_by);
        new.done_at := now();
      else
        new.done_by := null;
        new.done_at := null;
      end if;
    else
      new.done_by := old.done_by;
      new.done_at := old.done_at;
    end if;
  end if;
  return new;
end $$;

drop trigger if exists twoshare_lists_attribution on public.shared_lists;
create trigger twoshare_lists_attribution
  before insert or update on public.shared_lists
  for each row execute function public.twoshare_lists_attribution();

drop trigger if exists twoshare_list_items_attribution on public.shared_list_items;
create trigger twoshare_list_items_attribution
  before insert or update on public.shared_list_items
  for each row execute function public.twoshare_list_items_attribution();

-- 3. Privileges -------------------------------------------------------------------------
-- Supabase grants new public tables to anon and authenticated by default. Remove that and
-- grant only what the app needs. Column lists keep attribution columns out of the API.
revoke all on table public.shared_lists, public.shared_list_items from anon, authenticated;
grant select, delete on table public.shared_lists, public.shared_list_items to authenticated;
grant insert (title, emoji) on table public.shared_lists to authenticated;
grant update (title, emoji) on table public.shared_lists to authenticated;
grant insert (list_id, text) on table public.shared_list_items to authenticated;
grant update (text, done) on table public.shared_list_items to authenticated;

-- 4. Row Level Security: only the two TwoShare accounts (same allow-list as the feed) ---
alter table public.shared_lists enable row level security;
alter table public.shared_list_items enable row level security;

drop policy if exists "TwoShare lists read" on public.shared_lists;
drop policy if exists "TwoShare lists insert" on public.shared_lists;
drop policy if exists "TwoShare lists update" on public.shared_lists;
drop policy if exists "TwoShare lists delete" on public.shared_lists;
create policy "TwoShare lists read" on public.shared_lists for select to authenticated
  using (public.is_twoshare_user());
create policy "TwoShare lists insert" on public.shared_lists for insert to authenticated
  with check (public.is_twoshare_user() and created_by = auth.uid());
create policy "TwoShare lists update" on public.shared_lists for update to authenticated
  using (public.is_twoshare_user()) with check (public.is_twoshare_user());
create policy "TwoShare lists delete" on public.shared_lists for delete to authenticated
  using (public.is_twoshare_user());

drop policy if exists "TwoShare list items read" on public.shared_list_items;
drop policy if exists "TwoShare list items insert" on public.shared_list_items;
drop policy if exists "TwoShare list items update" on public.shared_list_items;
drop policy if exists "TwoShare list items delete" on public.shared_list_items;
create policy "TwoShare list items read" on public.shared_list_items for select to authenticated
  using (public.is_twoshare_user());
create policy "TwoShare list items insert" on public.shared_list_items for insert to authenticated
  with check (public.is_twoshare_user() and added_by = auth.uid());
create policy "TwoShare list items update" on public.shared_list_items for update to authenticated
  using (public.is_twoshare_user()) with check (public.is_twoshare_user());
create policy "TwoShare list items delete" on public.shared_list_items for delete to authenticated
  using (public.is_twoshare_user());

-- 5. Starter lists: created exactly once, ever ----------------------------------------
-- Recorded in twoshare_app_state, so they do not come back after you delete every list.
-- If lists already exist (e.g. an earlier test run), only the flag is recorded.
do $$
begin
  if not exists (select 1 from public.twoshare_app_state where key = 'shared_lists_seeded') then
    if not exists (select 1 from public.shared_lists) then
      insert into public.shared_lists (title, emoji)
      values ('Groceries', '🛒'), ('Bucket list', '✨'), ('Movies to watch', '🎬'), ('Places to try', '📍');
    end if;
    insert into public.twoshare_app_state (key) values ('shared_lists_seeded') on conflict (key) do nothing;
  end if;
end $$;

-- 6. Live updates --------------------------------------------------------------------
-- Realtime only sends changes for tables in the supabase_realtime publication and applies
-- each table's RLS, so only the two TwoShare users receive them. Missing tables are skipped.
do $$
declare t text;
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    create publication supabase_realtime;
  end if;
  foreach t in array array['messages', 'message_reactions', 'message_edits', 'calls', 'shared_lists', 'shared_list_items'] loop
    if to_regclass('public.' || t) is not null
       and not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;

commit;

-- Optional checks after running:
--   select tablename from pg_publication_tables where pubname = 'supabase_realtime';
--   select * from public.shared_lists;
