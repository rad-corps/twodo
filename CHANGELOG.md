# Changelog

Release notes for each version. `scripts/release.ps1` publishes the **Unreleased** section as the
GitHub release notes and renames it to the version, so this file and the releases always match.

## Unreleased

## v0.6.12 — 2026-10-10

**Times typed in calendar entries.** Type "Dentist 11:30am" (or "at 11:30 am", "7pm", "14:30", "3-4pm",
"at noon") and the time is set for you; "Dentist" is what's saved. Bare numbers ("at 3", "2-3 apples")
and prices ("11.30") are left alone. "3:30" with no am/pm means the afternoon. Setting the time
yourself always wins.

## v0.6.11 — 2026-10-10

**Edit list items.** Tap an item's words to change them, move it to a section, or delete it. Tick it
with its checkbox. (The ✕ on each row is gone: delete is in the item, so nothing goes by accident.)

**Sections in lists.** Add headings like "Dairy" or "Fruit & veg" (⋮ › Add a heading). Items below a
heading are in its section; put an item in a section from the item itself, or drag. Tap a heading to
rename or delete it — deleting a heading keeps its items. Headings don't count as things to do.
Older versions show headings as ordinary items.

## v0.6.10 — 2026-10-10

- The Notifications settings screen now uses the app's own theme instead of the look of the group you
  opened it from (its settings are for the whole app).

## v0.6.9 — 2026-10-10

**Your own colour.** Pick one in App settings (under your name). Everyone sees what you do in it: the
tick and "Sarah · 5 min ago" on items you tick, your name on calendar entries you add, in History, and
your circle in Group settings. Until you pick one, you have one chosen automatically.

## v0.6.8 — 2026-10-10

**Notification settings** (App settings › Notifications), for all your groups and lists:
- **Daily schedule** — what's on today, each morning at a time you choose (7:30 to start).
- **Reminders** before anything with a time: 10 minutes, 30 minutes or an hour before (off to start).
- Choose which changes by others to hear about: things added to the calendar, calendar changes, items
  added to lists, items ticked off (off to start), people joining or leaving, group changes, conflicts.
- Each kind has its own Android notification category, for sounds and vibration.

## v0.6.7 — 2026-10-10

- Fix: the app could crash in the background (seemingly at random) when Android restarted background
  sync after closing the app. Background sync now waits until the app is next opened instead.

## v0.6.6 — 2026-10-09

**Simpler calendar entries.** Adding or changing something on the calendar now uses one panel that slides
up from the bottom, wherever you start from:
- What's happening, then the day — **Today**, **Tomorrow** or **Pick a day…**
- **All day** or **At a time**, with big up and down buttons for the hour, minutes (in 15-minute steps)
  and AM/PM. Tap the time to type an exact one.
- One big **Add** / **Save** button; when changing an entry, **Delete** and its history are there too.

## v0.6.5 — 2026-10-09

- Lists: "Add an item" is now at the bottom of the screen, like the calendar, and the list scrolls to
  show what you've just added.
- **New list** is now at the bottom of the Lists tab.
- The People tab is now **Group settings** (still with the group's people and invites), and Settings in
  the group menu is now **App settings**.

## v0.6.4 — 2026-10-09

- **Optional crash reports.** Turn on Settings › Offer to email crash reports, and if the app closes
  unexpectedly it offers to email a report to the developer. It opens in your email app so you see what's
  sent (versions and where the error happened — nothing from your lists) and only goes if you send it.
- Settings switches now toggle when you tap anywhere on their row.

## v0.6.3 — 2026-10-09

- Calendars now open on the **Schedule** — everything coming up on one page. Tap a day's heading (or
  **Day**) to see a single day.
- A new calendar starts with one entry marking when it was created.

## v0.6.2 — 2026-10-09

- The calendar's "Add to …" box is now at the bottom of the screen, below the day's entries, and
  moves up above the keyboard while you type.

## v0.6.1 — 2026-10-09

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
