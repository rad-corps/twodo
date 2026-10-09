# Google Play — setup notes and drafts

Everything needed to get the app onto Play's **internal testing** track (up to 100 testers by email,
no public listing), and later production. The app is called **Intack** (app ID `app.intack`, site
`https://intack.app`).

## Checklist

**You (once):**
1. Create a Google Play developer account (one-off US$25) and complete identity verification.
2. Create the app in Play Console: name, default language, *App* (not game), *Free* for now.
3. **App signing:** when asked, choose to **use your own key** and upload the existing one from
   `~/.twodo/release.jks` (Play Console walks you through exporting it with its PEPK tool). This keeps
   Play and GitHub builds interchangeable, so people can switch without losing lists.
4. Testing › Internal testing: create an email list with the testers' Google accounts, upload the
   `.aab` that `scripts/release.ps1` prints, roll out, and send testers the opt-in link.

**Forms (answers drafted below):** app access, ads, content rating, target audience, data safety,
privacy policy URL, foreground service declaration, store listing.

**Assets:** icon 512×512 (`docs/play/icon-512.png`, a placeholder from the launcher icon), phone
screenshots (`docs/play/screenshots/`, 1080×2400), feature graphic 1024×500
(`docs/play/feature-graphic.png`, rendered from `feature-graphic.html`; re-render after retaking the
screenshots — the command is at the top of that file).

The screenshots use demo family data. To retake them (e.g. after UI changes): start two
emulators with the debug build installed and run `bash scripts/screenshots.sh` (it replaces the debug
app's data on both; `ADB=` can point at adb if it isn't on the PATH).

For production later: new personal developer accounts must first run a **closed test with at least 12
testers for 14 days** (check the current rule in Play Console); internal testing doesn't need that.

## App ID

`app.intack` (it was `app.twodo` before the rename). It's shown in the Play Store URL and **can never
change** once uploaded to Play: a different ID is a different app, and installs don't carry over. The
display name can still change freely.

## App links

Share links (`https://intack.app/join/#…`) open the app directly because the manifest's `autoVerify`
filter is backed by `docs/.well-known/assetlinks.json`. That file lists the SHA-256 fingerprint of the
key that signs the installed app. With Play App Signing that's **Play's app signing key** (Play Console ›
Test and release › App integrity shows it), which is the same as `release.jks` if you upload your own key
as above. If Play generates a key instead, add its fingerprint to the list alongside `release.jks`'s, so
both GitHub and Play installs verify. Get the local one with:

```
keytool -list -v -keystore ~/.twodo/release.jks | grep SHA256
```

## Store listing (drafts)

Written for people who just want a shared family list and calendar, without technical terms.

**App name** (max 30 chars): Intack

**Short description** (max 80 chars):

> Shared lists & family diary. Pay once — no subscription, no account, no ads.

**Full description** (max 4000 chars):

> Keep the family organised — shopping lists, to-dos and a shared diary — without a subscription and
> without handing your life to a big company.
>
> **Pay once, use it for good.** No monthly fees, no account to create, no ads.
>
> **Your information stays yours.** Lists and diaries live on your family's phones, not in someone
> else's cloud. When they're sent between phones they're locked with a key that only your family has.
>
> **Share in seconds.** Show a QR code, or send a link by message. Whoever you share with sees every
> change as it happens — tick something off at the shops and it's ticked at home.
>
> **Works even when you're apart.** Changes reach everyone, even if their phone was off at the time —
> they catch up as soon as it's back on.
>
> **What you get:**
> • Shared lists — tick items off, drag them into order, see who ticked what
> • A shared diary — swipe between days, add times, see the week at a glance
> • A full history of who changed what, and when
> • A notification when someone joins, or changes something while you're away
> • Colour themes, so every list and diary can have its own look
>
> No account. No sign-in. No subscription. Just your family's lists, on your family's phones.

**Category:** Productivity (alternative: Lifestyle). **Tags:** shopping list, to-do, family calendar.

**Contact details:** an email address is required (Play shows it publicly). **Website:**
`https://intack.app`

**Privacy policy URL:** `https://intack.app/privacy/`

## App content forms

**App access:** All functionality is available without special access (no login).

**Ads:** No, the app doesn't contain ads.

**Content rating questionnaire:** Category *Utility, productivity, communication, or other*. Answer
*No* to violence, sexuality, language, controlled substances, gambling. For "Does the app allow users to
interact or exchange content?" answer **Yes** — users share lists and see each other's names and
entries, but only with people they explicitly share a code with; there's no public content, chat with
strangers, or location sharing. Expected rating: *Everyone* / PEGI 3.

**Target audience:** 18 and over (keeps it out of the Families programme requirements; families use it
through the adults' phones). Not designed for children.

**Data safety** (recommended answers — check each against Play's current wording):
- *Does your app collect or share any of the required user data types?* **No.** The developer receives
  nothing. List contents leave the phone only **end-to-end encrypted** (WebRTC DTLS plus AES-GCM with the
  list's key) to the people the user chose to share with, via relays that can't read them — Play's
  guidance treats end-to-end encrypted data that only the sender and recipients can read as not
  collected. No analytics, crash reporting, ads or account.
- *Is all user data encrypted in transit?* **Yes.**
- *Do you provide a way for users to request that their data be deleted?* Data is only on users' devices:
  remove a list in the app or uninstall.
- Third-party servers involved (for the reviewer notes if asked): public WebTorrent trackers and STUN
  servers (see IP address and a random list code), public Nostr relays (see IP, timing, size, encrypted
  payload). None are operated by the developer.

**Foreground service declaration** (the app uses `FOREGROUND_SERVICE_SPECIAL_USE`):
- *Description:* "Optional background sync (on by default, can be turned off in Settings). The app has
  no server: shared lists sync directly between family members' phones, which requires an open
  connection to be reachable. The foreground service keeps those connections open so changes made on
  one phone reach the others promptly while the app is closed. A persistent notification tells the user
  it's running."
- *User impact if deferred:* family members' changes don't arrive until the app is opened, and the
  other person's phone can't be reached to deliver theirs.
- If Play pushes back, alternatives: `dataSync` type (limited to 6 h/day on Android 15+), or rely on the
  15-minute background check plus relay delivery (which already works while the app is closed).

**Camera permission:** used only to scan QR codes (declared by the QR library).
