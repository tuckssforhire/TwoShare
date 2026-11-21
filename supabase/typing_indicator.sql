-- TwoShare 1.0.39: private Realtime channel for the chat "typing…" indicator.
--
-- Run once in Supabase -> SQL Editor (after shared_lists_and_realtime.sql). Safe to re-run;
-- runs in one transaction. Nothing is stored: Realtime Authorization checks these policies
-- when a phone joins the private "twoshare-typing" broadcast channel.

begin;

do $$
begin
  if to_regprocedure('public.is_twoshare_user()') is null then
    raise exception 'public.is_twoshare_user() is missing. Run supabase/setup.sql first.';
  end if;
  if to_regclass('realtime.messages') is null then
    raise exception 'realtime.messages is missing. Realtime Authorization is not available on this project.';
  end if;
end $$;

drop policy if exists "TwoShare typing receive" on realtime.messages;
drop policy if exists "TwoShare typing send" on realtime.messages;

-- Only the two TwoShare accounts can listen to and send on this one topic.
create policy "TwoShare typing receive" on realtime.messages for select to authenticated
  using (public.is_twoshare_user() and realtime.topic() = 'twoshare-typing' and extension = 'broadcast');

create policy "TwoShare typing send" on realtime.messages for insert to authenticated
  with check (public.is_twoshare_user() and realtime.topic() = 'twoshare-typing' and extension = 'broadcast');

commit;
