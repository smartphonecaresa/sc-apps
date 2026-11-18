# Device-control app — setup (once)

This folder has the full native Android source for `SC-Shop.apk` and
`SC-Reseller.apk`. It is a thin shell: a WebView that loads your existing
web app (`repairshop.smartdukkanpos.com` / `repairhub.smartdukkanpos.com`)
and a small bridge (`SCNative`) that the web app already calls for ring,
lock, location, photo, uninstall-block, app-block and wipe.

## 1. Push this folder into `smartphonecaresa/sc-apps`

Copy `android/` and `.github/workflows/build-apk.yml` into the repo root
(next to the existing `SC-Shop.apk`/`SC-Reseller.apk`) and push to `main`.

## 2. Add 4 repo secrets (GitHub → repo → Settings → Secrets and variables → Actions)

A signing keystore is already generated for you in `keystore/sc-release.jks`
(also base64-encoded in `keystore/sc-release.jks.b64`). Add these secrets:

| Secret name | Value |
|---|---|
| `SC_KEYSTORE_BASE64` | the full contents of `keystore/sc-release.jks.b64` |
| `SC_KEYSTORE_PASSWORD` | `SCDevice@2026#Lock` |
| `SC_KEY_ALIAS` | `sc-release` |
| `SC_KEY_PASSWORD` | `SCDevice@2026#Lock` |

**Keep `keystore/sc-release.jks` somewhere safe outside git** (it's in
`.gitignore` so it won't be committed). Every future update to this app
must be signed with the *same* keystore, or Android will refuse to install
the update over the old one — if you lose this file, future updates need a
fresh install (uninstall + reinstall) on every phone instead of an update.

## 3. Run it

Push to `main` (or run the workflow manually from the Actions tab). GitHub
Actions builds both flavors, signs them with the secret above, and publishes
them as a new GitHub Release. Because `releases/latest/download/SC-Shop.apk`
and `.../SC-Reseller.apk` are exactly the filenames it publishes, your
existing web app's download links start serving the new build automatically
— nothing else to change.

## 4. Enrol a customer's phone as Device Owner (for the strong controls)

Uninstall-block, app-block and silent wipe only work once the phone is set
up as **Device Owner** — this has to be the very first thing installed on
a factory-reset (or newly-added-user) phone; it cannot be added later to an
already-set-up phone. Use the "Scan a QR" / "USB (ADB)" buttons already in
Platform Admin → Devices, which the web app already generates for this.
Without Device Owner provisioning, the app still runs and can Ring and
(if the person grants permission) report location/battery/photo, but
cannot block uninstall, block apps, or wipe.

## What I could not verify here

I don't have a real Android phone or the Android SDK in this environment,
so none of this has been tested on a device — only read carefully against
Android's own documented APIs. Please test the full flow (QR enrolment →
lock → location → ring → photo → unblock → unlock) on one real phone
before relying on it for customers' phones. If something doesn't behave as
expected, tell me what happened and I'll fix the source.

## What was intentionally left out

"Request a photo" takes one photo with the front camera when the shop asks
for it. Android (10+) always shows its own camera-in-use indicator while
this happens — that's a platform privacy protection built into Android
itself, and this app makes no attempt to hide it. If you want the customer
to also see an explicit on-screen notice ("The shop has requested a photo")
before/while it happens, tell me and I'll add that — right now it is silent
beyond Android's own indicator, matching how the admin UI already describes
this feature ("Request a photo").
