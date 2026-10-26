-- TwoShare backend setup for Supabase.
-- Run this once in Supabase Dashboard -> SQL Editor.

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80)
);

create table if not exists public.posts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  image_path text not null,
  created_at timestamptz not null default now()
);

create table if not exists public.likes (
  post_id uuid not null references public.posts(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (post_id, user_id)
);

create table if not exists public.comments (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  text text not null check (char_length(text) between 1 and 500),
  created_at timestamptz not null default now()
);

alter table public.profiles enable row level security;
alter table public.posts enable row level security;
alter table public.likes enable row level security;
alter table public.comments enable row level security;

-- SECURITY DEFINER avoids recursive RLS when policies check the profiles table.
create or replace function public.is_twoshare_user()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (
    select 1 from public.profiles p where p.id = auth.uid()
  );
$$;

revoke all on function public.is_twoshare_user() from public;
grant execute on function public.is_twoshare_user() to authenticated;

-- Re-create policies safely.
drop policy if exists "TwoShare profiles read" on public.profiles;
drop policy if exists "TwoShare posts read" on public.posts;
drop policy if exists "TwoShare posts insert" on public.posts;
drop policy if exists "TwoShare likes read" on public.likes;
drop policy if exists "TwoShare likes insert" on public.likes;
drop policy if exists "TwoShare likes delete own" on public.likes;
drop policy if exists "TwoShare comments read" on public.comments;
drop policy if exists "TwoShare comments insert" on public.comments;

create policy "TwoShare profiles read"
on public.profiles for select
to authenticated
using (public.is_twoshare_user());

create policy "TwoShare posts read"
on public.posts for select
to authenticated
using (public.is_twoshare_user());

create policy "TwoShare posts insert"
on public.posts for insert
to authenticated
with check (public.is_twoshare_user() and user_id = auth.uid());

create policy "TwoShare likes read"
on public.likes for select
to authenticated
using (public.is_twoshare_user());

create policy "TwoShare likes insert"
on public.likes for insert
to authenticated
with check (public.is_twoshare_user() and user_id = auth.uid());

create policy "TwoShare likes delete own"
on public.likes for delete
to authenticated
using (public.is_twoshare_user() and user_id = auth.uid());

create policy "TwoShare comments read"
on public.comments for select
to authenticated
using (public.is_twoshare_user());

create policy "TwoShare comments insert"
on public.comments for insert
to authenticated
with check (
  public.is_twoshare_user()
  and user_id = auth.uid()
  and char_length(text) between 1 and 500
);

-- Private bucket. 15 MB max per image.
insert into storage.buckets (id, name, public, file_size_limit)
values ('photos', 'photos', false, 15728640)
on conflict (id) do update
set public = false, file_size_limit = 15728640;

drop policy if exists "TwoShare photo read" on storage.objects;
drop policy if exists "TwoShare photo insert" on storage.objects;

create policy "TwoShare photo read"
on storage.objects for select
to authenticated
using (
  bucket_id = 'photos'
  and public.is_twoshare_user()
);

create policy "TwoShare photo insert"
on storage.objects for insert
to authenticated
with check (
  bucket_id = 'photos'
  and public.is_twoshare_user()
  and (storage.foldername(name))[1] = auth.uid()::text
);

-- AFTER creating the two Auth users in the Dashboard, add their UUIDs here.
-- Example only; replace both UUIDs and names, then run these two INSERTs:
-- insert into public.profiles (id, name) values ('FIRST-USER-UUID', 'Person 1');
-- insert into public.profiles (id, name) values ('SECOND-USER-UUID', 'Person 2');
