# Deploying the API to Fly.io

One always-on machine (see `fly.toml`: Game Night state lives in one process). Commands run from `server/` in PowerShell.

## 1. Account and tool
1. Create an account at fly.io and add a card.
2. Install the CLI: `iwr https://fly.io/install.ps1 -useb | iex`, then open a new terminal and run `fly auth login`.

## 2. The app
App names are global, so pick one nobody has, e.g. `thebridge-api-<yourname>`, and put it in `fly.toml` (`app = "…"`).
Pick the region closest to most users (`fly platform regions`), e.g. `lhr` (London) or `iad` (US East), and set `primary_region`.

```powershell
fly apps create <app-name>
```

## 3. Database, Redis and file storage
```powershell
fly postgres create --name <app-name>-db --region <region> --vm-size shared-cpu-1x --volume-size 3 --initial-cluster-size 1
fly postgres attach <app-name>-db --app <app-name>      # sets DATABASE_URL
fly redis create                                        # Upstash; copy the redis:// URL it prints
fly storage create --app <app-name>                     # Tigris; sets BUCKET_NAME, AWS_* (the server reads them)
```
Before public launch, move to Managed Postgres (`fly mpg create`) for backups and failover.

## 4. Secrets
Generate the server's own keys (each prints one value):
```powershell
npm run keygen                                                                        # JWT_PRIVATE_KEY_PEM
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"          # JOURNAL_KEK
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"       # MEDIA_SIGNING_SECRET
```
Keep JOURNAL_KEK safe: losing or changing it makes every journal unreadable.

Set them with `fly secrets set NAME=value …` (values with spaces or newlines in quotes):

| Secret | Value |
|---|---|
| `JWT_PRIVATE_KEY_PEM`, `JOURNAL_KEK`, `MEDIA_SIGNING_SECRET` | generated above |
| `JWT_ISSUER` | `https://<app-name>.fly.dev` (or `https://api.thebridge.app` once the domain points here) |
| `REDIS_URL` | from `fly redis create` |
| `REPLICATE_API_TOKEN`, `MODEL_PLANNER`, `MODEL_COACH`, `MODEL_FAST`, `MODEL_IMAGE`, `MODEL_IMAGE_EDIT` | as in your local `.env` |
| `GOOGLE_CLIENT_IDS` | the Google **web** client id |
| `FCM_SERVICE_ACCOUNT_JSON` | the Firebase service-account file, on one line |
| `RESEND_API_KEY`, `MAIL_FROM` | when the Resend domain is verified |
| `REVENUECAT_WEBHOOK_SECRET` | when RevenueCat is set up |
| `APNS_KEY_P8`, `APNS_KEY_ID`, `APNS_TEAM_ID` | when the Apple account exists |
| `ANDROID_CERT_SHA256`, `APP_STORE_ID` | for invite links, once the apps are in the stores |

## 5. Deploy and check
```powershell
fly deploy                      # builds the Dockerfile; runs migrations first (release_command)
curl https://<app-name>.fly.dev/healthz
npm run check-ai                # with your local .env: confirms the Replicate token and models
```

## 6. Point the apps at it
- Android release builds: `./gradlew :app:bundleRelease -Pbridge.releaseApiBase=https://<app-name>.fly.dev`
- iOS: set `API_BASE_URL` in `ios/project.yml` (Release settings) the same way.
- Later, a custom domain: `fly certs add api.thebridge.app` (and `thebridge.app` for invite links), then the DNS records it shows.
