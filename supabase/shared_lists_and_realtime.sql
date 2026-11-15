-- TwoShare: Shared Lists + live chat/call updates
-- Run once in Supabase -> SQL Editor. Safe to run again.

-- 1. Shared lists ------------------------------------------------------------
create table if not exists public.shared_lists (
  id uuid primary key default gen_random_uuid(),
  title text not null check (char_length(title) between 1 and 60),
  emoji text not null default '📝' check (char_length(emoji) <= 16),
  created_by uuid references public.profiles(id) on delete set null default auth.uid(),
  created_at timestamptz not null default now()
);

create table if not exists public.shared_list_items (
  id uuid primary key default gen_random_uuid(),
  list_id uuid not null references public.shared_lists(id) on delete cascade,
  text text not null check (char_length(text) between 1 and 200),
  done boolean not null default false,
  added_by uuid not null references public.profiles(id) on delete cascade default auth.uid(),
  done_by uuid references public.profiles(id) on delete set null,
  created_at timestamptz not null default now(),
  done_at timestamptz
);

create index if not exists shared_list_items_list_idx on public.shared_list_items(list_id, created_at);

alter table public.shared_lists enable row level security;
alter table public.shared_list_items enable row level security;

-- Both TwoShare users can see and edit every shared list (same allow-list as the feed).
drop policy if exists "TwoShare lists read" on public.shared_lists;
drop policy if exists "TwoShare lists insert" on public.shared_lists;
drop policy if exists "TwoShare lists update" on public.shared_lists;
drop policy if exists "TwoShare lists delete" on public.shared_lists;
create policy "TwoShare lists read" on public.shared_lists for select to authenticated using (public.is_twoshare_user());
create policy "TwoShare lists insert" on public.shared_lists for insert to authenticated with check (public.is_twoshare_user() and created_by = auth.uid());
create policy "TwoShare lists update" on public.shared_lists for update to authenticated using (public.is_twoshare_user()) with check (public.is_twoshare_user());
create policy "TwoShare lists delete" on public.shared_lists for delete to authenticated using (public.is_twoshare_user());

drop policy if exists "TwoShare list items read" on public.shared_list_items;
drop policy if exists "TwoShare list items insert" on public.shared_list_items;
drop policy if exists "TwoShare list items update" on public.shared_list_items;
drop policy if exists "TwoShare list items delete" on public.shared_list_items;
create policy "TwoShare list items read" on public.shared_list_items for select to authenticated using (public.is_twoshare_user());
create policy "TwoShare list items insert" on public.shared_list_items for insert to authenticated with check (public.is_twoshare_user() and added_by = auth.uid());
create policy "TwoShare list items update" on public.shared_list_items for update to authenticated using (public.is_twoshare_user()) with check (public.is_twoshare_user());
create policy "TwoShare list items delete" on public.shared_list_items for delete to authenticated using (public.is_twoshare_user());

-- Starter lists (only when there are no lists yet).
insert into public.shared_lists (title, emoji, created_by)
select t.title, t.emoji, null
from (values ('Groceries', '🛒'), ('Bucket list', '✨'), ('Movies to watch', '🎬'), ('Places to try', '📍')) as t(title, emoji)
where not exists (select 1 from public.shared_lists);

-- 2. Live updates -------------------------------------------------------------
-- Realtime only sends changes for tables in the supabase_realtime publication.
-- It still applies each table's RLS, so only the two TwoShare users receive them.
do $$
declare t text;
begin
  foreach t in array array['messages', 'message_reactions', 'message_edits', 'calls', 'shared_lists', 'shared_list_items'] loop
    if exists (select 1 from pg_tables where schemaname = 'public' and tablename = t)
       and not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = t) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end $$;
