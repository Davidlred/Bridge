# Hosting the API for testers (free): Render + Supabase + Upstash

This runs the same server as your laptop, on free plans:

| Piece | Service | What it holds |
|---|---|---|
| Server | Render (free web service) | The API, chat and Game Night |
| Database | Supabase (free project) | All app data |
| Files | Supabase Storage | Photos and goal images |
| Redis | Upstash (free) | Live chat and game messages between server instances |

About 45 minutes the first time. Keep every key and password out of chat and out of the repo. Paste them only into the
Render dashboard.

**Free-tier limits to know:** Render sleeps after 15 minutes without traffic, and the first request after that takes
30 to 50 seconds. A Game Night running when it falls asleep is lost. Step 6 shows how to keep it awake.

---

## 1. Supabase: database
1. Go to supabase.com, sign up, then **New project**.
   - Name: `the-bridge`.
   - Region: the one closest to your testers (for Nigeria, `West EU (London)` or `Central EU (Frankfurt)`).
   - Database password: generate one and save it in your password manager.
2. When the project is ready, click **Connect** in the top bar.
3. Find **Session pooler**, not "Direct connection". Render can't reach Supabase's direct address. Copy the URI. It looks like:
   `postgresql://postgres.abcdefgh:[YOUR-PASSWORD]@aws-0-eu-west-2.pooler.supabase.com:5432/postgres`
4. Replace `[YOUR-PASSWORD]` with the password from step 1. This whole line is your **DATABASE_URL**.

You don't need to create any tables. The server creates them on its first start, and it turns on row-level security
so Supabase's public web API can't read them.

## 2. Supabase: file storage
1. In the same project: **Storage**, then **New bucket**, named `bridge-media`. Leave **Public bucket** off.
2. Open **Storage**, then **Settings** (or **S3 Connection**):
   - Copy the **Endpoint**, e.g. `https://abcdefgh.supabase.co/storage/v1/s3`. This is **S3_ENDPOINT**.
   - Copy the **Region**, e.g. `eu-west-2`. This is **S3_REGION**.
3. Under **S3 Access Keys**, click **New access key** and copy both values:
   - The Access key ID is **AWS_ACCESS_KEY_ID**.
   - The Secret access key is **AWS_SECRET_ACCESS_KEY**. It's shown once.
4. **S3_BUCKET** is `bridge-media`.

## 3. Upstash: Redis
1. Go to upstash.com, sign up, then **Create database** (Redis).
   - Name: `bridge`.
   - Region: the same area as Supabase.
   - Plan: Free.
2. On the database page, copy the **`rediss://...`** URL. It starts with `rediss` (two s's) and includes the password.
   This is your **REDIS_URL**.

## 4. The server's own keys
In PowerShell, from the `server` folder, run each line. Each prints one value:
```powershell
npm run keygen                                                                        # JWT_PRIVATE_KEY_PEM (one long line)
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"          # JOURNAL_KEK
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"       # MEDIA_SIGNING_SECRET
```
Save **JOURNAL_KEK** somewhere safe. If it's lost or changed, every journal becomes unreadable.

You also need:
- **REPLICATE_API_TOKEN**: the same one as in your local `server/.env`.
- **GOOGLE_CLIENT_IDS**: the same value as in your local `server/.env`.

## 5. Render: the server
1. Go to render.com and sign up with GitHub. Allow it to see the `Davidlred/Bridge` repo.
2. Click **New**, then **Blueprint**, pick the `Bridge` repo, and choose the branch `milestone-1-foundation`.
   Render reads `render.yaml` from the repo.
3. Render asks for each secret. Paste the values from steps 1 to 4.
   - For **JWT_ISSUER**, use `https://bridge-api.onrender.com`. If Render gives the service a different address,
     change JWT_ISSUER to that exact address later, under **Environment**, then **Save**.
4. Click **Apply**. The first build takes about 5 minutes.
5. Open `https://<your-service>.onrender.com/healthz`. You should see `{"ok":true,"service":"the-bridge"}`.
6. Send me the address. I'll build the APK against it.

## 6. Optional: keep it awake
Free Render services get 750 hours a month, which is enough to run one service all the time. To stop it sleeping:
1. Sign up at uptimerobot.com (free).
2. Add an **HTTP(s)** monitor on `https://<your-service>.onrender.com/healthz`, every 5 minutes.

## What's already limited for testers
- **AI_DAILY_BUDGET = 300** AI calls a day across everyone: coach, plans and images. Chat moderation is never capped.
  When the budget runs out, the coach says it's unavailable until midnight UTC. Change the number in **Environment**.
- **Per person per day:** 30 coach messages, 8 images and 20 Reality Checks (unchanged).
- **COMMUNITY_OPEN_NOW = true**: testers can make alliances without the 7-day streak. Set it to `false` before launch.

## Not set up yet (the app still works without them)
- **Push notifications:** these need Firebase (Android) and an Apple Developer account (iOS). Without them, events
  still show in the app.
- **Email** (Resend): sign-in by email works; emails like password resets are skipped.
- **Payments** (RevenueCat): Plus can't be bought yet.
