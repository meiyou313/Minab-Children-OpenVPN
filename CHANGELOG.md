# Minab changelog

All notable changes, newest first. Each GitHub release shows only its own version's notes.

New in v0.9.6.4 — eight fixes from testing v0.9.6.3:
the ping and real-test results are fixed columns now (ping
always on the LEFT, real always on the RIGHT, in Home and in
Configs, whatever the phone's locale direction is; an empty
column keeps its place instead of letting the other badge
slide into it). The bulk-import group picker moved to the TOP
of the import page, right under the hint, with a bold label
and a boxed spinner — it used to sit at the very bottom of
the long scrolling page, so it was never seen. The real-test
status line now counts down the TOTAL time left for the whole
run (current server's remaining seconds plus one wait per
server still to test), not one server's 12 s. The test
sentence lives in a distinct card now, in larger Vazirmatn
in the primary colour, in Home and in Configs. The connect
button visibly animates at rest too: it loops slowly while
disconnected (it used to freeze on its first frame and look
like a plain static icon), loops at full speed while
connecting, and plays once on connect. The Home list no
longer lags during a test: list refreshes are merged to at
most one re-bind every 400 ms, the per-result disk save
moved off the UI thread, and the Configs full rebuild per
result is a coalesced re-bind instead. The Real test
settings panel is smaller still (6dp padding, 4dp sections,
10sp labels, 9sp hints, 28dp steppers). Release notes are
generated from the tag now, so a release page can never
again show another version's notes.

New in v0.9.6.3 — seven items from testing v0.9.6.2:
the connect button is now an animated Lottie power orb (it loops
as a spinner while connecting and plays once on connect; the disc
still shows the state colour). The Real test settings panel is
smaller and more minimal, with smaller fonts and steppers. Both
test columns are now cleared at the start of BOTH tests, so an old
real-test result can never sit next to fresh ping values — the code
has no path that writes a ping result into the real-test column;
what looked like that was a stale real result from an earlier run.
On bulk import the group picker now has "Create group" first and
"All" as the default; a group you pick always wins over
auto-detection, and the country auto-detection now understands
vpnbook-style names like de20/us16/fr2311 and also runs after a
single-file import (which never categorised before). During a real
test the connect button dims, the progress row gets a green halo,
and the status line counts down the seconds left for the server
under test (the wait from the settings); the Cancel button in that
row stops the test. The core is already at ics-openvpn 0.7.69 —
the submodule pins match the tag, so there was nothing to update.

New in v0.9.6.2 — six fixes from testing v0.9.6.1:
the Real test settings are now an inline panel directly
under the Real test button (Home AND Configs) instead of
the ugly dialog — the gear is green while the panel is
open and blue while it is closed, and every change saves
at once (the dialog only saved on OK, so a change lost
by closing it any other way looked like the setting was
ignored). New defaults: 5 healthy servers to find,
test-unpinged off, wait 12 s. A ping test now clears all
old results first, so the badges go blank until the new
results arrive. The real test honours "test unpinged"
off: with it off it always re-pings first, so the filter
judges fresh results, and it never connects a server
without a fresh ping-OK. The number setting now counts
SUCCESSES: the run keeps going through the ranked list
until that many servers pass, instead of testing only
the first N and stopping even when they all fail. And
after a ping test the servers are sorted by ping,
lowest first, in Home and in Configs (within each group
in Configs — the group order is kept). The crash
reporter stays. Please test the settings panel, a real
test with unpinged off, and the ping sort.

New in v0.9.6.1 — eleven fixes from testing v0.9.6:
the update check finally works for real (all Minab
releases are pre-releases, so GitHub's "latest release"
endpoint always answered 404 — and the web fallback's
redirect points at the releases list, not a tag — so
the check failed for everyone, always, whatever the
connection; it now reads the releases list, API first,
then the releases page, and the version comparison
already handles four-part versions, so 0.9.6.1 is
correctly newer than 0.9.6); Data used: Weekly and
Monthly are now 12 bars with 6 visible and the rest by
swiping left, the current bar (today / this week /
this month) is green in all three modes, a jump icon
at the bottom-right of the card jumps to the current
bar, tapping any bar shows its date or range, and the
tall bar's value ("1.54 GB") is no longer cut to
"1...." — the Weekly labels ("9 Nov") are readable
now too; Home server rows: names are one line at 10sp
with the group below, so nothing is cut at the row's
bottom edge (Configs rows were fine and are untouched);
during a real test the panel no longer flips between
connect and disconnect — it shows a steady "Testing
servers… (3 of 12)" state; and a settings gear next to
the Real test button lets you choose the wait per
server (recommended 12 s), how many ping-answering
servers to test (recommended 10), and whether servers
that did not answer the ping are tested too
(recommended: yes). The crash reporter stays.
Please test the update check, the charts, and a real
test with the new settings.

New in v0.9.6 — the tab-switch crash is FIXED, and this
time the cause is proven, not guessed: the phone's own
crash report (sent twice, identical) showed a
ClassCastException in HorizontalScrollView
.measureChildWithMargins — the "Data used" bar strip on
the Graph tab was given plain ViewGroup.LayoutParams,
but a HorizontalScrollView's child must have
MarginLayoutParams, so measuring the strip crashed the
app on the main thread every time the Graph page was
laid out during a tab switch (since v0.9.1, when the
50-bar Daily strip was added). The strip now gets the
correct FrameLayout.LayoutParams. The built-in crash
reporter stays, in case anything else ever closes the
app. Please test tab switching — tap and swipe, all
tabs, connected and not.

New in v0.9.5 — ending the tab-switch crash for real:
switching tabs has closed the app since v0.9.1, and three
rounds of fixes reasoned from the code did not stop it,
because no crash trace ever existed. This version records
one itself: a crash report (full stack trace) and the
last steps before the close are saved on the phone, and
the next launch shows them in a "گزارش خرابی" dialog
with Share — if it still closes on a tab switch, please
share that report, and the exact cause can finally be
fixed instead of guessed. The audit in this version also
guarded the last unguarded switch paths (Settings resume,
Configs view creation, the Graph timer handler, the Home
always-on lookup). All v0.9.3 / v0.9.4 Home fixes are
kept (own IP before connecting, tunnel IP + country
flag after, smaller server names).

New in v0.9.4 — urgent fix: v0.9.3 crashed on launch
(only the app logo showed, then it closed, before any
screen appeared). Tabs now load lazily again, as in
v0.9.2 — the eager creation of all five tab views at
startup is what changed in v0.9.3 — and every fragment's
startup path is now guarded, so a failure while creating
a tab's view is caught and logged instead of killing the
app. All v0.9.3 Home fixes are kept (own IP before
connecting, tunnel IP + country flag after, smaller
server names), and the tab-switch work from v0.9.3
(skip the rebuild when nothing changed, ViewHolders)
stays too.

New in v0.9.3 — fixes from testing v0.9.2:
- Switching tabs no longer hangs and closes the app: all
  five tab views now stay alive, and a tab switch skips
  the list rebuild and row re-measure when nothing
  changed (the old code rebuilt and re-measured every
  row on every switch — with dozens of imported servers
  that froze the UI past the ANR threshold). A single
  bad profile or a stale state update can no longer kill
  the app on a tab switch either.
- The IP row now shows the device's own public IP before
  connecting, and the tunnel's exit IP after connecting
  (the old code checked while still connecting, so the
  field stayed "—" for the whole connection); the flag
  of the country that IP belongs to appears right next
  to the IP, and the check now tries ipinfo.io, then
  ip-api.com, then ipify.
- Server names are smaller (11sp) with "…" when they
  still do not fit, so long names like
  "us-free-51.protonvpn.tcp" no longer wrap awkwardly
  and get cut off at the row's bottom edge (Home and
  Configs).

New in v0.9.2 — fixes from testing v0.9.1:
- Switching tabs no longer hangs and closes the app: the
  connected-profile lookup on the UI thread no longer
  retries with full disk reloads (a stale last-connected
  server could block it for 10+ s), the stale UUID is
  cleared on disconnect, the widget refresh no longer
  runs under the VpnStatus lock, and the byte-count
  poller really stops after disconnect.
- The blue toolbar now shows "Minab OpenVPN" next to the
  backpack icon (the 432 px logo squeezed the title out;
  a 144 px logo is used and the title is set explicitly).
- While connecting, the notification shows the
  black-and-white backpack icon (blinking between two
  backpack renderings) instead of the old stock key icon.

New in v0.9.1 — 16 fixes from testing v0.9:
- About: trimmed to version + extra features; the update
  result now shows right under the Check button; the
  update check is more robust (longer timeout + a
  fallback when api.github.com is unreachable).
- Per-app VPN: "Bypass" mode added — all apps use the
  VPN except a few.
- Data used: live counts fixed (cross-process prefs +
  re-scheduled byte polling), Daily tab by default, and
  a 50-bar swipeable strip (46 past days + today + 3
  future empty bars).
- Home and Configs tabs no longer lag on tap or swipe.
- Configs explanatory texts shortened.
- Configs rows: edit pencil + star on the right, neat
  ping/real columns like Home, no username under
  configs, a flag for every server.
- New gold "favorites" tab first in Home and Configs;
  starred servers show in their country group and in
  favorites.
- "Manage categories" moved before "+ new group";
  a pencil reorders groups (the order is saved).
- Search field added to the Configs tab.
- Auto-failover only on server-side drops (never on a
  manual disconnect) and only to servers known healthy.
- No more disconnect confirmation dialog — one tap on
  the main button.
- Home: duplicate title removed, backpack icon moved
  into the blue toolbar, memorial line shown fully.
- Connected panel redesigned: button on the left,
  Status / Name / IP / Ping on the right, colour-coded;
  tapping the ping re-tests that server.
- Ping test and real test fixed: working servers no
  longer fail (probes are advisory only; the full
  tunnel test decides).
- Server editor no longer hangs and closes the app.
- Switching servers manually from the Home list is
  smoother.

New in v0.9:
- About is much shorter now (80–90% trimmed): what the
  app is, the version (at the top AND the bottom), the
  GitHub links, the thanks to ics-openvpn, and the list
  of Minab's extra features. A "Check for updates"
  button sits at the top of About.
- Update notice on Home AND as a phone notification
  when a new version is published (at most once per
  version; only when the checker positively finds a
  genuinely newer release).
- Configs tab: the server list now sits in the same
  Configs window (one scroll for the whole page), and
  the Delete / Share buttons are no longer half-cut.
- Bulk import: the top text is rewritten in plain words
  (pick many .ovpn files or a zip, one shared
  username/password for all, then import), and the
  header is fully visible now — it used to start
  mid-sentence.
- FAQ: the first card's title is no longer clipped at
  the top; all text is smaller and in Vazirmatn; 8 new
  questions with complete answers cover the new
  features (best server, failover, IP check, search,
  widget, per-app VPN, import from a link, full backup).
- New launcher icon: exactly the pink-backpack photo
  (no AI, no redraw, no zoom or crop), fitted inside
  the adaptive-icon safe zone; the Home header uses the
  same photo with its background removed.
- "Connect to best server": one button connects the
  fastest healthy server from the last tests (best
  real-test result, falling back to best ping).
- Auto-failover: when the connected server drops, Minab
  connects to the next healthy server by itself (up to
  3 tries, then it stops and says so). No test runs on
  the switch — it just switches and shows a message
  with the new server's name. (Works while the app is
  open; a setting in Settings turns it off.)
- After connecting, the current public IP and country
  show under the connect button (checked through the
  tunnel; "—" when it cannot be checked). No speed
  test — there is none anywhere in the app.
- Search servers by name or country on Home, and star
  (favorite) servers so they stay at the top.
- Home-screen widget: shows the state and the connected
  server; tap it to connect or disconnect.
- Per-app VPN (split tunneling): pick which apps go
  through the VPN and which go direct (in Settings).
- Import from a link: paste a URL to a .ovpn file or a
  .zip of .ovpn files; Minab downloads and imports it
  like files picked by hand.
- Full backup: "Back up configs" now also saves the
  groups, per-profile usernames/passwords, favorites
  and settings in the same zip, and restoring it (from
  the bulk import screen) puts everything back exactly.
  The plain .ovpn-only zip export still works as before.
- Ping fix: a TCP ping now needs a real OpenVPN reply —
  before, a middlebox answering the handshake made far
  servers read as 1–3 ms while the real test said
  Blocked. ICMP replies are checked against the target
  and impossible sub-5 ms values to public servers are
  dropped. A failed probe never shows as a small green
  number anymore.
- The top-bar "+" is gone (it opened the empty-profile
  dialog, not an import, and it closed the app); the
  bulk-import button is the "+" now. The duplicate
  flow still works, and an empty profile name is
  refused plainly instead of closing the app.
- The real-test pre-check retries once on a transient
  failure, so one lost probe no longer marks a server
  Blocked.

New in v0.8:
- The app is now called "Minab OpenVPN" (launcher,
  toolbar and home header), and the app icon sits next
  to the name on Home.
- The GitHub repo is now "Minab Children OpenVPN"
  (github.com/meiyou313/Minab-Children-OpenVPN).
- About is rewritten around this project: the GitHub
  profile and repo links come first, and the thanks to
  the ics-openvpn and OpenVPN projects are at the end.
- Settings text is smaller and more minimal, and the
  empty icon space on the left is gone.
- FAQ is rewritten: only questions about this app —
  bulk import, groups, ping test, real test, data used,
  copy/move and updates.
- Configs tab: the "Uncategorized" tab moved to the end,
  just before "+", like on Home.
- Configs tab: tick configs and use the new Copy / Move
  actions next to Delete to copy or move them to
  another group.
- Home: the small ping icon next to each group name is
  gone. "Ping test" / "Real test" now test only the
  selected group (or everything when "all" is selected).
- "Test ping" is renamed "Ping test" everywhere.
- Graph tab: the Daily / Weekly / Monthly chips are
  fixed — no more half-cut text.
- Swiping between tabs needs a longer drag now, so
  accidental tab switches are rarer.
- Real test is faster: a dead server is skipped after a
  ~2 s reachability pre-check instead of burning the
  full 12 s / 20 s connect wait. (A real test still
  connects each server in turn — Android allows only
  one VPN tunnel at a time, so parallel real tests
  like proxy apps do are not possible with OpenVPN.)

New in v0.7:
- The app is now called "Minab Open Vpn" (launcher,
  toolbar and home header); the memorial line stays.
- New app icon: a pink backpack with white flowers,
  like the reference photo — plus a white silhouette
  version for the VPN notification icon.
- Home buttons (Import / Test ping / Real test) no longer
  get clipped on narrow screens.
- The hint sentence under the connect button is gone;
  the test explanation is now two short lines.
- The "Uncategorized" group tab moved to the end,
  just before "+".
- Ping and real badges are fixed-width columns now, so
  they line up neatly.
- The connected server's row is highlighted in green,
  and its name under the connect button is small, with
  its ping next to it — tap the ping to re-test it.
- After a ping test the list sorts by ping again, and
  after a real test it sorts by the real results
  (last test wins; the sort now survives switching tabs).
- The connect button is ~30% smaller.
- Graph tab: "Data used" bar charts — real measured
  download + upload, daily (last 7 days), weekly
  (last 8 weeks) and monthly (last 6 months), each bar
  labelled with its exact value. The live speed graphs
  are still there below.
- Settings: "Show speed in notification" is now the
  first option (still on by default).
- Fixed: some servers wrongly showed a green
  "Real: 0 ms" badge. Those profiles were never
  real-tested — an old saved value was read as a
  measurement. A real fetch can never take 0 ms, so
  such values are now ignored and recorded as Blocked.

New in v0.6: Real test — the "Real test" button on Home
actually connects each server in turn and fetches the
Telegram web client (web.telegram.org) through the tunnel,
so the result proves the server really bypasses filtering
(a plain ping cannot prove that). It pings first when
needed, tests fastest servers first, and stops as soon as
3 servers have passed. Working servers show a "Real:
<ms>" badge and sort first.

v0.5.1 HOTFIX: v0.5 crashed on every launch (white screen,
then the app closed). Cause: the new single-scroll Home
asked ListFragment for its ListView while the fragment
had no view yet (in onCreate), which throws. Fixed by
posting the resize on the fragment's own view instead.
Everything else is the same as v0.5.

New in v0.5:
- The repo is now "Minab Children"
  (github.com/meiyou313/Minab-Children-OpenVPN); the app is still
  just "Minab".
- Releases carry their version in the title and the APK
  file name ("Minab debug Beta v0.6").
- FAQ moved out of the bottom tab bar to the end of
  Settings; the tab bar is smaller and each tab has a
  faint frame, so all five tabs fit without scrolling.
- The home header shows a memorial line next to "Minab":
  "In memory of the 168 innocent children of Minab
  Elementary School, who took flight to heaven far too
  soon."
- Action buttons and group tabs are smaller and more
  minimal; the group bar fades at the right edge so it
  is clear it scrolls; the first tab reads "all" and the
  "+" tab is last.
- Config rows are minimal, with the country's flag before
  the name (36 most-used countries bundled, tiny
  vectors); the home list scrolls together with the whole
  home page now.
- Configs tab: tick boxes to select configs and delete
  the selected ones, or share them as one zip file; the
  Share button also shares one group or all configs.
  The credentials buttons now explain that they set the
  VPN username/password the configs use to log in.
- The top bar has add / bulk-import / settings icons on
  every tab, not only on Home.
