# TwoShare 1.0.39 — test build (not released)

- "typing…" in chat over a private Realtime channel (nothing stored). Requires
  `supabase/typing_indicator.sql`; without it chat works as before, minus the indicator.
- Unread chat badge on the feed updates live.
- Save photo to phone (Pictures/TwoShare) from the photo viewer, using the cached copy.
- Shared login background for both phones (from the 1.0.38 integration branch); requires
  the shared_settings part of `supabase/shared_lists_and_realtime.sql`.

# TwoShare 1.0.38 — test build (not released)

- New Lists tab: shared groceries, bucket list, movies, places and custom lists.
- Live updates for chat, reactions, edits, incoming calls and lists, with automatic
  reconnection and a polling backup.
- Shared login background (built-in, random or your own photo) for both phones, set in
  Account & settings; each phone keeps a local copy for the sign-in and lock screens.
- Chat refresh downloads only the reactions and edits for the messages on screen.
- Build-time source rewriting removed; local and GitHub Actions builds use the same source.
- Requires `supabase/shared_lists_and_realtime.sql` before the Lists tab and live updates work.
- Updates 1.0.37 in place when signed with the permanent release key (see SIGNING.md).

# TwoShare 1.0.37 — 14 November 2026

- Polished login and biometric lock screens, theme surfaces, typography and navigation.
- Full-screen Now Together caption preview for both starting and replying.
- Improved photo caching and prefetching, with less disruptive refresh errors.
- Permanent release signing key with a separately retained recovery backup.

## One-time installation change

This replacement uses a new signing certificate because the old key is unavailable.
It cannot update the older official app in place. Finish uploads and save local drafts,
download the new APK to a normal Downloads folder, uninstall the older official TwoShare,
then install this APK and sign in again. Server-stored photos, messages and calendar
entries remain attached to the same account. Local drafts and caches may be removed
when uninstalling. TwoShare UI Test is a separate package and can be removed separately.

Future releases signed with the new permanent key will update version 1.0.37 normally.
