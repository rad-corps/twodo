# Changelog

Release notes for each version. `scripts/release.ps1` publishes the **Unreleased** section as the
GitHub release notes and renames it to the version, so this file and the releases always match.

## Unreleased

- Fix: saving a group's background photo didn't take effect for most photos (and could clear the
  previous one). Group changes now always finish, even when the screen closes straight away.

## v0.6.0 — 2026-10-09

**Groups.** Share one calendar and your lists with the same people — your household, say — by
inviting them once. A group has three tabs: **Calendar**, **Lists** and **People**.
- Starting a group gives it a calendar and a shopping list; add more lists any time. Everyone in the
  group has the same calendar, so nobody ends up with two.
- **Invite** someone once and they get the calendar and every list, including ones added later.
- Already have lists? When you start a group you can use your diary as its calendar and bring your
  lists along. Lists outside groups are still there under **Other lists**.

**Make it yours.** Give a group a **background photo** — its colours can be taken from the photo — or
pick colours and a highlight colour. Everyone in the group sees the same look.

**Schedule view.** Calendars can show everything coming up on one page (Calendar › Schedule). In more
than one group? **All calendars** puts them together.

Also:
- **Text size** setting (Normal, Large, Larger).
- Simpler wording: "In sync with Sarah" instead of connection details.
- A welcome screen for new users.

## v0.5.1 — 2026-10-09

- Maintenance release to check the release pipeline from a new build machine. No changes to the app.

## v0.5.0 — 2026-10-09

**Themes.** Pick a colour theme for the app (Settings › Theme) and give any list or diary its own
(⋮ › Theme) to tell them apart at a glance. Fifteen to start: TwoDo Dark and Light, Solarized Dark and
Light, Nord and Nord Light, Dracula, Gruvbox Dark and Light, Catppuccin Mocha and Latte, Tokyo Night,
Rosé Pine and Rosé Pine Dawn, and One Dark. Themes are just for your phone — they aren't shared.

**Icons.** Lists and diaries now have their own icons (a checklist and a calendar), on the home screen —
in each one's theme colours — next to the name when it's open, and when creating something new.
The old dark mode switch is replaced by the theme setting (dark mode off becomes TwoDo Light).

## v0.4.0 — 2026-10-08

**Lists now sync even when the phones can't connect directly — and when one of them is off.**
- When two phones can't reach each other (common on mobile data), changes now go through free public
  Nostr relays instead. They're end-to-end encrypted: the relays only ever see scrambled data.
- Changes made while the other phone is off are kept on the relays for 14 days and arrive as soon as it
  comes back — the two phones no longer need to be online at the same time.
- Joining works through the relays too: the new phone asks, and anyone in the list who's online sends it.
- The status line shows "Syncing with Sam via relay" when that's the route being used.

Also:
- Fix: after a large sync, the last part could stay unsaved until the next sync.
- Settings > Connection log also shows relay activity.

## v0.3.3 — 2026-10-08

- **Much less background traffic and battery use.** When the other phone can't be reached, TwoDo now looks for it less and less often (up to every 2 minutes) instead of every 10 seconds, and looks quickly again as soon as you open the app, change network, or show a share code.
- Smoother connecting: cleaning up failed connection attempts no longer holds up the rest of the app.

## v0.3.2 — 2026-10-08

- **Faster joining when TwoDo is already running:** the app now keeps its connections to the meeting-point servers open and shares them between lists, so a newly opened share link connects in about a second.
- **The sharing phone looks harder while its QR/share dialog is open**, so the other phone is found straight away.
- **Settings > Connection log:** shows what happened while connecting (with timings). If joining is slow, tap Copy and send the log over so we can see where the time went.

## v0.3.1 — 2026-10-08

**Much faster joining.** Opening a shared list or diary now connects and syncs in about 2 seconds (was 30-60 s, and sometimes didn't finish). Big diaries sync in a few seconds.

**You can see what's happening while someone joins:**
- The joining phone shows "Connecting… / Looking for the other phone… / Getting the list…", then "Joined “Shop” with Adam".
- The sharing phone's QR dialog shows "Waiting for someone to scan…", then "✓ Sam joined".

**Sync in background is now on by default**, so the other phone can reach yours while TwoDo is closed (you can turn it off in Settings).

Also: connection attempts that stall are retried after 5 s instead of 15, and the status line only says "Offline" when the phone really has no network.

## v0.3.0 — 2026-10-08

**New: shared diaries.** New > Diary. Opens on today; swipe (or the arrows) to change day, tap a day in the week strip, or tap the date for a calendar where you can also type a date. Add entries with an optional time; tap one to edit, move or delete it.

**New: History.** Every list and diary keeps a shared log of who changed what and when (menu > History; diary entries also show their own history).

Also:
- Big lists and diaries now sync (updates over ~256 KB used to fail).
- Conflicting diary edits are reported with the day/time that won.
- Replaced two public meeting-point servers that had stopped working.
- Faster, smaller release build.

Update every phone before sharing a diary: 0.2.x doesn't know about diaries and could strip entries' dates when editing them.

## v0.2.1 — 2026-10-08

- Fix: "Remove from this phone" now really removes the list. On 0.2.0 the list came back (including after a restart). Lists you tried to remove on 0.2.0 are still there; remove them again after updating.

## v0.2.0 — 2026-10-08

- Notifications: someone joining or leaving a list, and a quiet summary of changes others make while TwoDo is closed (Settings toggle). Changed rows briefly light up while you're looking at the list.
- Share links are now normal web links (tappable in Messenger etc.) that open TwoDo. Older twodo:// links still work.
- Join a list from a screenshot of its QR code (Join list > Choose screenshot, or share the image to TwoDo).
- Removing a list tells the others.

Update all phones before sharing new lists: v0.1.0 doesn't understand the new links.

## v0.1.0 — 2026-10-08

First release: shared lists over peer-to-peer sync, QR sharing, drag to reorder, who-ticked-what, conflict notices, dark mode.
