# TwoShare

A minimal private Android image-sharing app for exactly two authorized users.

## Version 0.1

- Email/password login
- No registration screen
- Shared chronological photo feed
- Upload from Android gallery/photo picker
- Like / unlike
- Comments
- Private Supabase Storage bucket
- Row Level Security restricts data to the two approved account UUIDs
- Automatic feed refresh every 5 seconds and comments refresh every 3 seconds

## Stack

- Android / Kotlin / Jetpack Compose
- Supabase Auth
- Supabase Postgres + Row Level Security
- Supabase Storage (private bucket)
- Coil 3 for displaying signed image URLs
- GitHub for source control

## 1. Create the Supabase project

Create a Supabase project. In **Authentication -> Users**, manually create the two email/password accounts. Do not add a sign-up screen to the app.

## 2. Set up the database and private image bucket

Open **SQL Editor** and run:

`supabase/setup.sql`

Then copy the UUID of each of the two Auth users and run:

```sql
insert into public.profiles (id, name)
values ('FIRST-USER-UUID', 'Person 1');

insert into public.profiles (id, name)
values ('SECOND-USER-UUID', 'Person 2');
```

Those two `profiles` rows are the allow-list. A valid Supabase Auth account without a matching profile cannot read the feed or the private photo bucket.

## 3. Connect the Android app

Open:

`app/src/main/java/com/twoshare/app/data/SupabaseConfig.kt`

Replace:

- `YOUR_SUPABASE_URL`
- `YOUR_SUPABASE_PUBLISHABLE_KEY`

Use only the client **publishable/anon key**. Never place a `service_role` key, password, signing key, or other secret in this public repository or Android app.

## 4. Build

Requirements:

- JDK 17
- Android Studio compatible with AGP 8.13.x
- Android SDK 36

Open the project in Android Studio, allow Gradle to sync, and run on the phone.

## Public repository security

This repository is public, but the shared photos are not. Access is enforced by Supabase authentication, Row Level Security, and the private Storage bucket. Keep all service/admin secrets out of GitHub.

## Current MVP limits

- 15 MB maximum per uploaded image
- Images are not compressed yet
- Feed uses lightweight polling instead of Realtime subscriptions
- No public registration, profiles, followers, download button, messaging, or admin screen
