# ROU Track — From Scratch

*A study document for explaining this project in an interview. Written from the actual code as of v4.4 (versionCode 5), branch `version.4`.*

---

## 1. The one-paragraph version

ROU Track is an Android app that field workers (like sales reps or delivery staff — "LWs" based on the terminology in your logs) keep running in the background all day. It quietly records where the phone is, batches those points up, and ships them to a PHP/MySQL server so an office dashboard can draw the worker's route on a map and confirm they actually went where they were supposed to. It's not a chat app or a game — it's infrastructure. Its entire job is: **know where the phone was, reliably, without killing the battery, and get that data to the office even when the network is flaky** — and do all of that while running invisibly for 8+ hours without the user having to think about it.

---

## 2. Why does this need to exist at all?

Think about what the alternative looks like without an app like this: a field worker fills in a paper log of where they went, or the office just... trusts them. Neither of those scales, and neither is verifiable.

The real-world problem is **"how do we know a remote worker actually visited the places they were supposed to, and how do we get proof of that automatically, cheaply, and without the worker having to do anything"**. That's the whole reason this exists. A few consequences fall out of that one requirement:

- It has to run **in the background**, because nobody's going to open an app every 5 minutes all day.
- It has to survive the phone being locked, other apps taking over, and Android's aggressive battery-saving trying to kill it — because if tracking silently stops, the office has no idea and just sees a blank map (this is a real bug we hit and fixed — more on that later).
- It has to work on bad or intermittent internet, because field workers are often in areas with patchy signal — so it can't assume "send this location right now" will succeed.
- It has to be honest about *how good* the location is — a location fix pulled from a cell tower when there's no GPS signal is basically useless for proving someone visited a specific building, and the app now tags every point with how trustworthy it is.

---

## 3. Every concept you need, explained from zero

I'm assuming you can code, but nothing about Android or this specific stack. Read this section before the "walkthrough" section — it defines all the vocabulary that section uses.

### 3.1 What an Android app actually *is* (four building blocks)

An Android app isn't one big `main()` function like a normal program. The OS starts and stops different pieces of your app independently, whenever it wants. There are four kinds of pieces ("components") this app uses:

- **Activity** — one screen. `LoginActivity`, `DashboardActivity`, `CameraActivity` are all Activities. When you "open the app," you're really launching an Activity.
- **Service** — code that runs *without a screen*, in the background. `LocationTrackingService` is the heart of this whole app — it's the thing that keeps recording GPS points even after you've locked your phone and put it in your pocket.
- **BroadcastReceiver** — a piece of code that wakes up when the *system* announces something happened, like "the phone just finished booting" (`BootReceiver`) or "an alarm you scheduled just went off" (`AlarmReceiver`, which is now mostly inert — see section 5).
- **Worker (from WorkManager, a Google library)** — a scheduled background task the OS is allowed to delay slightly, used for things that don't need to happen at an exact second. `ServiceWatchdogWorker` and `ServiceStartWorker` are this app's two workers.

**Analogy:** think of an Activity like a shop's front counter (only exists while a customer is there), a Service like a warehouse worker who keeps working even when no customer is in the shop, and a BroadcastReceiver like a doorbell — dormant until something rings it, then it does one small thing and goes back to sleep.

### 3.2 Foreground Service — why the persistent notification exists

Starting with Android 8+, if a background Service isn't "important" in some visible way, the OS will kill it within seconds to save battery — you literally cannot run silent background code forever anymore. Google's rule: if you want to run continuously, you must be a **foreground service**, which *requires* showing the user a permanent, un-dismissable notification (that's the "ROU Track Active" notification you always see in the shade). It's the trade Android forces on you: "you can keep running, but the user must always be able to see that you're running."

There's a second, harsher rule layered on top (Android 12+): when you *start* a foreground service, you have about 5 seconds to actually put up that notification, or Android crashes your app with `ForegroundServiceDidNotStartInTimeException`. This bit us directly — see section 8.

### 3.3 GPS, "fused location," and what "accuracy" really means

Your phone doesn't have one location system, it has three, and Android blends them for you:

- **GPS/GNSS** — actual satellites. Slow to lock (can take 10-30s), needs sky view, but very precise (3-30 meters).
- **Wi-Fi positioning** — Google keeps a giant database mapping Wi-Fi router IDs to physical locations (built from millions of phones). If your phone sees a known router, it can guess your location from that, no satellites needed. Fast, but only as accurate as Google's map of that router's location (can be very wrong if a router moved).
- **Cell tower positioning** — the crudest fallback. Which cell tower you're talking to tells roughly which few-kilometer area you're in. Fast, always available if you have signal, but can be 500-1500 meters off.

Android's "Fused Location Provider" (the `FusedLocationProviderClient` this app uses) automatically blends whichever of these it has and hands you one `Location` object. That object always comes with an `accuracy` value — Android's own confidence radius, in meters, for how close the reported point is to your real position. **A low accuracy number is a *good* fix; a high one is bad.** A phone reporting `accuracy: 1000m` isn't broken, it's just telling you honestly "I only know roughly which town you're in" — which happens when it's fallen back to cell-tower-only positioning because it can't see GPS satellites or known Wi-Fi.

**Analogy:** GPS is like reading a wall clock precisely. Wi-Fi positioning is like glancing at someone's watch across the room — usually fine, occasionally very wrong if their watch is broken. Cell tower is like guessing the time of day from how bright it is outside — better than nothing, wildly imprecise.

### 3.4 Room (the local database) and why an app needs one at all

**Room** is Google's library for using SQLite (a real, tiny SQL database that lives as a single file on the phone) from Java without hand-writing raw SQL everywhere. You define:
- **Entities** — plain Java classes annotated to represent a database table (`LocationTrack`, `Session`, `User` in this app).
- **DAOs** (Data Access Objects) — interfaces where you write SQL queries as annotations, and Room generates the actual database code for you (`LocationTrackDao`).

Why does this app need a local database instead of just sending each point straight to the server? Because **the internet isn't always there**. If the app tried to send every GPS point live and failed the moment there was no signal, that point would just be lost forever. Instead: **save locally first, always. Send to the server later, whenever possible.** The local database is the safety net — nothing is ever lost just because the network dropped for ten minutes.

### 3.5 SharedPreferences — the phone's "sticky notes"

`SharedPreferences` is Android's simplest storage: a small file of key-value pairs (like a tiny settings dictionary) that survives even if the app process is killed and restarted. This app uses it (via `SessionManager`) for things that need to persist but don't belong in the structured database — "is the user logged in," "what's the last GPS point we saved" (so a restarted service can pick up exactly where it left off), "has this crash already been logged so we don't log it twice."

### 3.6 HTTP, REST-ish endpoints, and "polling"

The app talks to the server the plain, old-school way: it makes an HTTP POST request (using Java's built-in `HttpURLConnection` — no fancy networking library like Retrofit here) to a specific `.php` file URL, sends data (either as JSON or as a multipart file upload, same as a web form with a file attached), and gets a JSON `{"success": true/false, ...}` response back.

There's no permanent "always-connected" pipe between the app and server (that would be a WebSocket or push notification system) — instead, the app just **polls**: every ~2 minutes, it wakes up, asks "do I have anything unsent? Ok, send it," and goes back to sleep. This is simpler and more battery-friendly than a persistent connection, at the cost of the office's dashboard never being *perfectly* live — there's always up to a couple of minutes of lag. (See section 9 for the honest trade-off here.)

### 3.7 The server side: PHP + MySQL + PHPMailer

The backend is plain PHP scripts (no framework like Laravel — just `.php` files that each handle one job) talking to a MySQL database. `sync_locations.php` is the one that receives location batches; `send_report.php` is the one that emails a CSV report. PHP here is doing exactly what you'd expect a small API to do: read the POST body, validate it, run a database query, write a JSON response.

**PHPMailer** is a widely-used PHP library for sending email through an actual mail server (rather than PHP's very unreliable built-in `mail()` function). It needs SMTP credentials — a username/password for a mail-sending account — the same way your email client needs your email password to send mail.

**mysqli** is PHP's interface for talking to MySQL. One important, specific gotcha you'll want to know cold if asked: as of PHP 8.1+, `mysqli` **throws an exception on any query error by default**, instead of the old behavior of just returning `false`. This app got bitten by exactly that (section 8).

### 3.8 SMTP, and why sending email from a server is its own small nightmare

**SMTP** is the actual protocol used to send email (as opposed to IMAP/POP, which is for *reading* email). To send mail *as* an account, you need to "authenticate" with that mail provider's SMTP server — basically prove you're allowed to send as that address.

Gmail is built for *people* sending email from *one place* (their laptop, their phone) — it gets suspicious and blocks logins from a server, or requires special "App Passwords" instead of your real password, and can silently revoke them. That's exactly what happened to this project twice (section 8) — which is why it now uses **Brevo**, a service purpose-built for exactly this ("transactional email," meaning automated emails sent by an app/server, as opposed to marketing newsletters). Brevo also has its own security feature worth knowing about: **IP authorization** — it only accepts SMTP logins from servers whose IP address you've explicitly told it to trust, which is a smart anti-theft feature (if someone steals your SMTP password, it's useless without also being on your server) but also a very confusing wall to hit blind, since it fails with a generic "could not authenticate" error that looks exactly like a wrong password.

### 3.9 NTP and "why not just use the phone's clock"

**NTP (Network Time Protocol)** is how computers get the real, correct time off the internet from dedicated time servers (`pool.ntp.org` here). Why does this app bother? Because a user could just... change their phone's clock. If the app trusted `System.currentTimeMillis()` for the "when was this GPS point recorded" timestamp, someone could set their phone's clock back an hour and make it look like they were somewhere earlier than they really were. So the app calibrates an **offset** from a source the user can't tamper with (the NTP server, and even better, GPS satellite time — satellites carry atomic clocks and their time signal can't be faked by changing a phone setting) and computes "true time" as `offset + elapsedRealtime()`, where `elapsedRealtime()` is a clock that only measures *time elapsed since boot*, which a user also can't fake by changing the date in Settings.

### 3.10 Doze mode, battery optimization, and why "just keep running" is hard

Modern Android actively fights background apps to save battery — this is called **Doze mode** and **App Standby**. If your app isn't specifically exempted, the OS will delay your network requests, freeze your alarms, and eventually kill your service outright, especially on aggressive manufacturer skins (Xiaomi/MIUI is notoriously the worst — you'll see this app specifically handling and warning about it). "Battery optimization exemption" is a special permission the user has to explicitly grant (there's a system settings screen for it) that tells Android "don't apply those restrictions to this specific app."

### 3.11 WorkManager and the "watchdog" pattern

**WorkManager** is Google's library for scheduling background work that should eventually run, even across app restarts and device reboots, without you having to hand-manage `AlarmManager` timers yourself. This app uses it for exactly one thing: a **watchdog** that wakes up every 15 minutes and checks "is my tracking Service actually still alive? If not, restart it." It's a second line of defense in case the Service itself gets killed by the OS — see section 8 for the full three-layer strategy.

### 3.12 Git branching in this project, briefly

The commit history shows a `version.N` branch pattern (`version.2`, `version.3`, `version.4`) — each major version gets its own long-lived branch, with a long series of small commits merged back via GitHub pull requests (`Merge pull request #42...`). It's a solo/small-team workflow, not a strict trunk-based CI/CD setup — there's no automated test suite gating merges (see section 9), so quality control here is manual: test on a real device, read the logs, ship it.

---

## 4. A tour of the folders — and *why* it's laid out this way

```
app/src/main/java/com/rdxindia/ihbl/routrack/
├── LoginActivity.java, RegisterActivity.java      ← auth screens
├── DashboardActivity.java                          ← the main screen after login
├── CameraActivity.java                             ← photo/video capture tied to a location
├── FilterLocationActivity.java                     ← view/export/email a date-range CSV
├── FilterSessionActivity.java                      ← view login/logout session history
├── MapViewActivity.java                            ← draw a saved route on a map
├── AlarmReceiver.java, BootReceiver.java            ← system-event listeners
├── DataTypes.java                                   ← the single source of truth for record "type" codes
├── MainActivity.java                                ← DEAD CODE (see below)
│
├── database/           ← Room: entities + DAOs + the DB itself
│   ├── AppDatabase.java
│   ├── LocationTrack.java / LocationTrackDao.java   ← the big one — every GPS point ever saved
│   ├── Session.java / SessionDao.java               ← one row per login→logout period
│   └── User.java / UserDao.java                     ← local username/password cache
│
├── service/
│   └── LocationTrackingService.java                 ← THE core of the whole app (~1650 lines)
│
├── workers/
│   ├── ServiceWatchdogWorker.java                   ← "is the service still alive? restart if not"
│   └── ServiceStartWorker.java                      ← starts the service after boot
│
├── utils/               ← everything that isn't UI and isn't the DB
│   ├── ApiService.java       ← ALL network calls to the server live here
│   ├── SessionManager.java   ← SharedPreferences wrapper: login state, calibrated time, etc.
│   ├── DeviceInfoHelper.java ← reads phone state: GPS on?, signal strength, battery-opt status...
│   ├── NetworkMonitor.java   ← re-attempts server verification the moment internet reappears
│   ├── NtpTimeHelper.java    ← queries pool.ntp.org for tamper-proof time
│   ├── LogCapture.java       ← dumps recent logcat to a file for remote debugging
│   ├── CsvHelper.java        ← turns DB rows into a CSV file
│   └── ImageCompressor.java  ← shrinks photos before upload
│
└── adapter/             ← RecyclerView adapters (the "list of recent tracks" on the dashboard)

server/
└── send_report.php      ← the ONLY server file kept in this repo (the rest lives only on the server)
```

**Why this shape?** It's a pretty standard "package by layer" Android structure, not "package by feature" — all database code is together, all network code is together, all background-service code is together. For a project this size (one developer, one main feature) that's the right call: you almost never need to touch more than 2-3 files for any given change, and `service/LocationTrackingService.java` being its own package signals clearly "this is the important, separate concern" — everything else supports it.

One honest note: `MainActivity.java` exists (it's Android Studio's default template file, still has `EdgeToEdge.enable()` boilerplate) but **it's not registered in `AndroidManifest.xml` at all** — Android literally cannot launch it. It's inert leftover scaffolding from project creation. `LoginActivity` is the real entry point (it has the `<intent-filter>` with `MAIN`/`LAUNCHER` in the manifest, which is what makes an Activity the one that opens when you tap the app icon).

---

## 5. Walking through every major piece

### 5.1 `LoginActivity` — the entry point

What it does: checks if you're already logged in (skip straight to Dashboard if so); otherwise takes mobile number + password, checks them against the **local** `User` table (login itself is fully offline-capable — you don't need internet to log into an account you've already registered on this device), then calls the server (`ApiService.verifyActivation`) to confirm the account is still authorized centrally.

What it deliberately does **not** do: it doesn't hash the password (see section 9 — this is a real gap), and it doesn't block login if there's no internet — it just marks the session as `PENDING_VERIFICATION` and lets `NetworkMonitor` (section 5.3) retry verification silently the moment connectivity returns. That's a deliberate design call: **a field worker should never be locked out of tracking just because they're in a dead zone.**

### 5.2 `DashboardActivity` — the main screen

Shows current location on an embedded Google Map, the 5 most recent tracked points (via a `LiveData` observer — Room automatically re-notifies the UI whenever the underlying table changes, so this list updates itself with zero manual refresh code), and buttons to filter/export records, take a photo, or log out.

It's also the piece that:
- Requests all the runtime permissions in the right order (location → background location → phone state), because Android makes you ask for dangerous permissions one at a time, at runtime, not just declare them in the manifest.
- **Binds** to `LocationTrackingService` (a different relationship than *starting* it — binding lets the Activity call methods directly on the running Service, like `getLastLocation()`, while starting is what keeps the Service alive independent of whether any Activity is bound to it).
- Hosts a hidden feature: **long-pressing the version number** (bottom of the screen) opens a dialog to capture and email recent logs to the office — a deliberate "escape hatch" for remote debugging non-technical field users, added specifically because there was no other way to get diagnostic info out of a phone hundreds of kilometers away.

### 5.3 `LocationTrackingService` — the heart of the app

This is the one file worth understanding in real depth, because it's almost certainly what an interviewer will ask about most. It's a `Service` (section 3.1) that, once started, is meant to run **all day**, whether or not any screen is open.

**`onCreate()` — the startup sequence, and *why the order matters***

The very first thing it does — before touching the database, before checking permissions, before anything else — is call `startForeground()`. This is not arbitrary ordering; it's a hard Android 12+ deadline (section 3.2, and the war-story in section 8). Only *after* that does it do the heavier setup: open the Room database, restore the last known GPS point from `SharedPreferences` (so a restarted service picks up exactly where it left off instead of starting from scratch), register a network-availability listener, acquire a **wake lock** (a flag telling Android "don't let the CPU fully sleep while I'm doing this" — needed briefly during setup so background work isn't paused mid-flight), start the location update stream, and schedule the periodic sync loop and the silent health-check loop.

**The location callback — the actual filtering logic (this is the algorithm)**

Every time Android's Fused Location Provider hands the service a new GPS fix (requested every 10 seconds, though the OS/hardware may deliver less often), the service has to decide: **do I actually save this as a record, or throw it away?** Saving *every single fix* would flood the database and the server with near-duplicate points and drain battery on constant syncing; saving *too rarely* means missing real movement. The decision, in order:

1. **Mock location check** — if the phone is running a "fake GPS" app (common way to cheat the system), always save it, but flag it (`datatype = MOCK`) so the office can see the fraud attempt rather than being fooled by it.
2. **First record ever** — always save (nothing to compare against yet).
3. **Moved ≥ 50 meters** from the last saved point — *this* is the interesting one, and it has three separate safety checks stacked on top of the raw distance number (fully explained with real numbers in section 6).
4. **Stationary heartbeat** — if 10 minutes have passed and the phone isn't moving, save anyway, so a worker standing still for hours still produces a trail (not just one point followed by silence).
5. **Safety net** — if 15 minutes have passed *no matter what*, save something — this is the fallback of last resort, guaranteeing the app can never go more than 15 minutes without producing a record, even if every other rule above rejected every fix.

**Syncing — `syncDataToServer()` and the batch logic**

Every ~2 minutes (with a random ±30 second "jitter" added — deliberately, so if hundreds of phones all started tracking at the same moment, they don't all hammer the server in the same instant), the service checks for unsynced records. If there are more than 10, it splits them into batches of 10 and sends them one batch at a time, waiting for each to finish (up to 30 seconds) before sending the next, with a 1-second pause between batches. Photos and videos sync separately, through their own upload calls, because they're much bigger files with their own timeout budgets.

**What it deliberately does NOT do:** it never sends a location live, the moment it's captured. There's no push/WebSocket path. Everything funnels through the same 2-minute polling loop. It also never *deletes* an unsynced record — a record only ever gets removed from the phone once the server has explicitly confirmed it (see `cleanupOldRecords()`, which only touches rows already marked `synced=1` and only if they're from before today).

### 5.4 `ApiService` — the only file that talks to the network

Every HTTP call in the entire app goes through this one class: checking if a mobile number is registered, verifying activation, syncing locations, uploading photos/videos, sending report emails, fetching server time. Centralizing it like this means there's exactly one place that knows the server's URL, one place that builds HTTP requests, one place with the "trust all SSL certificates" workaround (added because a spoofed device clock can otherwise make Android reject the server's HTTPS certificate as "not yet valid" or "expired" — a real problem for phones with a bad clock).

Worth calling out specifically: `parseJsonObjectLenient()`. The server is supposed to always return clean JSON, but a PHP misconfiguration can leak an HTML warning into the response body (`<br />&lt;b&gt;Warning...`) which breaks a normal JSON parse instantly. This method first tries a strict parse, and only if that fails, tries to salvage the `{...}` embedded inside the garbage — a defensive layer added *after* this exact failure mode caused location records to get permanently stuck (full story in section 8).

### 5.5 `database/` — Room entities and DAOs

`LocationTrack` is the big one: one row per saved GPS point, holding not just lat/lng but battery level, GPS/internet/flight-mode state, network signal strength, device model, and a free-text `textMsg` column that's become the app's general-purpose diagnostic channel (fix accuracy, health-check results, crash reasons — all packed into one string, explained in section 6/8). `Session` is one row per login-to-logout period. `User` is a locally cached username/password so login works offline.

`LocationTrackDao` is where every SQL query in the app lives, written as annotated interface methods — Room turns e.g. `@Query("SELECT COUNT(*) FROM location_tracks WHERE mobileNumber = :mobile AND synced = 0")` into real generated Java code at compile time, so you get compile-time checking that your SQL at least *looks* plausible against your table shape.

### 5.6 `CameraActivity` — photo/video tied to a location

Uses **CameraX** (Google's modern camera library — it exists because the older raw Camera2 API is famously painful to use correctly across the huge range of Android phone hardware; CameraX handles a lot of that variance for you). A captured photo gets compressed (`ImageCompressor`, target ~200KB, capped at 720×1280) before it's saved, specifically to keep upload sizes small over potentially slow field connections. Every photo/video record is stored as its own `LocationTrack` row (`datatype = PHOTO` or `VIDEO`), tagged with whatever location was active at capture time — so a photo isn't just a photo, it's proof-of-location.

### 5.7 `FilterLocationActivity` + `CsvHelper` — reporting

Lets the office (or the field worker) pick a date range, view the matching records, view them on a map (`MapViewActivity`, which just draws a `Polyline` connecting the points), or generate a CSV and email it via the server (`ApiService.sendReportEmail`). `CsvHelper` is a very plain, manual CSV writer — no external CSV library, just careful string-escaping — that turns a `List<LocationTrack>` into a `.csv` file on disk.

### 5.8 The reliability layer: `BootReceiver`, `AlarmReceiver`, and the two Workers

This is worth understanding as *one system*, because it's really about answering one question: **"how do we make sure the tracking service comes back, no matter what killed it?"**

- `BootReceiver` fires when the phone restarts. Its whole job, today, is to clear the login session so a rebooted phone forces a fresh login (a deliberate security/integrity choice — a session shouldn't silently survive a reboot the app didn't control).
- `ServiceStartWorker` (a WorkManager one-shot job) is what actually restarts the tracking service after that reboot — it has to be a Worker rather than code directly inside `BootReceiver`, because a plain `BroadcastReceiver` is *blocked* from calling `startForegroundService()` on modern Android (a background-launch restriction) — WorkManager runs in a context the OS considers a legitimate background-execution slot, so it's allowed.
- `ServiceWatchdogWorker` runs every 15 minutes forever, checking "is `LocationTrackingService` actually in the OS's running-services list right now? If not, start it again." This is the safety net for the case where the OS (or an aggressive OEM battery manager) silently kills the service mid-day without any reboot happening.
- `AlarmReceiver` **used to** be much more important — it fired a loud, full-screen "are you OK?" safety-check pop-up every 15-25 minutes. That entire feature was removed in a recent version (field workers found the constant interruption more annoying than useful, and it was also directly responsible for a real crash — see section 8). Today `AlarmReceiver` is intentionally a near-empty no-op class, kept only so any *already-scheduled* alarm from an old app version fires harmlessly into nothing instead of crashing.
- `LocationTrackingService.onDestroy()` *itself* schedules one more restart attempt, 5 seconds out, via a plain inexact `AlarmManager` alarm — a third, immediate layer on top of the 15-minute watchdog, for the common case where the service is killed and should just bounce straight back up.

So there are genuinely **three independent restart mechanisms** stacked on top of each other (`START_STICKY` return value + the 5-second `onDestroy` alarm + the 15-minute WorkManager watchdog), which sounds like overkill until you remember: if tracking silently stops for a whole shift and nobody notices until end-of-day, that's a full day of missing, unrecoverable data. Redundancy here is a deliberate, considered trade, not accidental complexity.

### 5.9 The server: `send_report.php`

The only server file kept in this repo (everything else — the sync endpoint, registration, verification — lives only on the production server and isn't checked into git, which is itself worth noting as a real gap; see section 9). It receives a mobile number, subject, body, and a CSV file via multipart form upload, and emails it through Brevo's SMTP relay to a **fixed, server-controlled recipient address** — the app can never specify who receives the email, which is a deliberate anti-abuse measure (otherwise a modified/decompiled copy of the app could be used to spam arbitrary addresses through your server).

---

## 6. One real worked example — the movement filter, with actual numbers

This is the single most interesting piece of logic in the app, and it's worth being able to walk through it with real numbers, because it's exactly the kind of thing an interviewer loves to poke at.

**The setup:** the phone was last saved at some point. A new GPS fix comes in that's `distanceMoved = 406.48135` meters away from that last saved point, with `location.getAccuracy() = 899.999` meters, `timeSinceLastSave = 39,000` milliseconds (39 seconds) — these are **real values pulled straight from a field device's actual logcat** during a diagnosis session on this project (a Xiaomi phone that had lost GPS satellite lock and fallen back to cell-tower positioning).

**Step 1 — is 406m even a candidate "move"?**
```java
DISTANCE_THRESHOLD_METERS = 50.0f
distanceMoved (406.48) >= DISTANCE_THRESHOLD_METERS (50.0)   →  TRUE, this could be a move
```

**Step 2 — is the fix's accuracy good enough to trust for a move decision?**
```java
MAX_MOVEMENT_ACCURACY_METERS = 35.0f
accuracyOk = accuracy (899.999) <= 35.0   →  FALSE
```
This fails immediately. A fix claiming to be within 900 meters of the truth cannot be trusted to prove someone moved 406 meters — the entire "movement" could just be measurement noise. **The record gets rejected here**, logged as `Move rejected: accuracy 899.999m too poor`, and the pending-move state is cleared.

**Step 3 (for contrast — what if the accuracy *had* been fine, say 15m?**
There's a second, independent check that would still apply — the **teleport guard**:
```java
MAX_PLAUSIBLE_SPEED_KMH = 150.0f
impliedKmh = (distanceMoved_km) / (timeSinceLastSave_hours)
           = (406.48135 / 1000) / (39000 / 3600000)
           = 0.40648 km / 0.010833 hours
           ≈ 37.5 km/h
```
37.5 km/h is a perfectly plausible speed for a vehicle, so *this specific example* would pass the teleport check even with good accuracy. But if the same 406m had instead happened in, say, 2 seconds instead of 39, the math would be:
```java
impliedKmh = (406.48135 / 1000) / (2 / 3600)
           ≈ 731.7 km/h
```
731.7 km/h is far above the 150 km/h ceiling — no field worker's phone can teleport that fast — so *that* fix would be rejected as physically implausible, regardless of what accuracy number it claimed. This is exactly the second layer needed because Wi-Fi-based positioning can sometimes report a *confident-looking, low* accuracy number while still being kilometers off — the accuracy check alone isn't bulletproof, so the speed sanity check catches what it misses.

**Step 4 — even though the move was rejected, does *something* still get saved?**
Yes — and this is the fix for a real bug (section 8). Time-based fallbacks are checked independently, in an `if (!shouldSave)` block, specifically so a rejected move can never *also* suppress the heartbeat/safety-net saves. If 15 minutes have passed since the last save regardless of any of the above, a record still gets saved — tagged in its `textMsg` as `Acc:900m Src:network`, so the office can see at a glance "this specific point is low quality, treat it as approximate," rather than either silently losing the worker's trail entirely, or worse, silently accepting a wildly wrong position as if it were precise.

---

## 7. One real end-to-end trace: a field worker walks 60 meters

Let's trace one concrete action all the way through the whole system, start to finish.

1. **Fix arrives.** Every 10 seconds, Android's Fused Location Provider hands `locationCallback.onLocationResult()` a new `Location`. This one: lat/lng 60 meters from the last saved point, `accuracy = 12m`, taken 3 minutes after the last save.
2. **Filter decision.** `distanceMoved (60) >= 50` → candidate move. `accuracyOk = 12 <= 35` → true. No `pendingMoveLocation` exists yet from a prior fix, so this one becomes the **pending** candidate — it is *not* saved yet, just remembered, with a timestamp.
3. **Confirmation fix.** ~10 seconds later, another fix arrives, still >50m from the anchor, still good accuracy. `pendingValid` is true (within the 2-minute confirmation window) → **this time it's saved.** (This two-fix requirement exists specifically so a single random GPS drift spike on a stationary phone can't fake a "move" — it has to happen twice in a row.)
4. **`saveLocationToDatabase()` runs.** On a background thread (never the main UI thread — Room queries and any real work are always pushed off `executorService` so the app never freezes), it: determines the record's `datatype` (just `NORMAL` here — not install, reboot, or mock), decides the phone isn't "stationary" (it moved, so no coordinate-smoothing kicks in), stamps the record with the server/GPS-calibrated **true time** (not the raw device clock), reads current battery %, builds the `textMsg` string (`"Acc:12m Src:fused | Loc:Ok, BgLoc:Ok, Net:Ok, Q:3, BatOpt:No, PlayPro:On, BkUsg:Allowed, Notif:On, Gnss:Ok"`), and calls `db.locationTrackDao().insert(track)`.
5. **The record now sits in SQLite with `synced = 0`.** It's safe — nothing about this step needed the internet at all.
6. **Up to 2 minutes later, `syncRunnable` fires.** It checks internet is available, fetches all `synced = 0` rows for this mobile number (there might be several by now), and since there are ≤10, sends them in one batch via `ApiService.syncLocations()`.
7. **`syncLocations()` builds a JSON array** of all the fields for each record, POSTs it as `application/json` to `sync_locations.php`, and blocks (via a `CountDownLatch`, capped at 30 seconds) waiting for the response.
8. **On the server**, `sync_locations.php` decodes the JSON, and for our record: checks the required fields are present, validates lat/lng are in range, checks whether a row with this exact `(imei, RecNo, sessionId)` already exists (dedup — this matters because the app might legitimately re-send the same record if a previous sync attempt's *response* got lost even though the insert succeeded — "at-least-once delivery," explained more in section 8), and since it's new, runs the prepared `INSERT` and adds this record's `RecNo` to the `syncedRecNos` array in the response.
9. **Server responds** `{"success": true, "synced": 1, "duplicates": 0, "syncedRecNos": [next_rec_no], ...}`.
10. **Back on the phone**, `ApiService` parses that (through the lenient parser, just in case), maps the confirmed `RecNo` back to this record's local database `id`, and calls `db.locationTrackDao().markAsSynced([id])` — flipping `synced` to `1`.
11. **The record is now considered "safe."** It'll sit in the local DB until `cleanupOldRecords()` deletes it — but only once it's from a day *before* today, so today's data always stays fully browsable in the app even after it's synced.
12. **Meanwhile, on the office side**, whoever queries the server's `data_yday_avt` table (via whatever dashboard/report tool reads it — that layer isn't in this repo) now sees this point and can draw it on the map you saw in the "ROU Monitoring System" screenshots — a red pin, 60 meters further along the worker's route than the last one.

---

## 8. The reasoning behind the biggest decisions (and the real bugs that shaped them)

This section is deliberately written like war stories, because that's how you'll actually talk about it in an interview.

**Why store-then-sync instead of send-immediately?** Because field connectivity is unreliable, and losing data is worse than delaying it. Every record is durable on the phone the moment it's created; the network is only ever a "when convenient" concern layered on top.

**Why an `else if` chain turned into a real production bug, and what it taught us.** Originally, the save-decision logic was one long `if / else if` chain: check mock → check first record → check distance-move → check heartbeat → check safety-net. That looks reasonable, but it has a subtle trap: if the distance-move branch is *entered* (because the phone moved far enough) but then internally *rejects* the fix (bad accuracy), the `else if` chain has already committed to that branch — the heartbeat and safety-net checks below it never even run. In production, this meant: a phone that lost GPS lock and kept producing far-away, low-accuracy fixes would enter the move-branch on *every single fix*, get rejected every time, and as a direct result **never fall through to the time-based safety net either** — tracking would go completely, silently dark for as long as the phone stayed GPS-blind, sometimes hours. The fix was to restructure this so the time-based fallbacks are checked in their own independent `if (!shouldSave)` block that always runs regardless of what happened in the distance branch. The lesson generalizes: **an `else if` chain silently couples branches that should be independent — anywhere "reject and fall through to a different check" is the intended behavior, that coupling is a bug waiting to happen.**

**Why `startForeground()` had to move to the very first line of `onCreate()`.** Android 12+ gives a foreground service ~5 seconds from `startForegroundService()` to actually call `startForeground()`, or it's killed with `ForegroundServiceDidNotStartInTimeException`. This app originally did a bunch of setup first — opening the database, reading SharedPreferences, checking for a previous crash, acquiring a wake lock — *then* called `startForeground()`. On a slow device, or under memory pressure, that setup could occasionally blow past 5 seconds, and the crash would get logged pointing at whatever *triggered* the restart (`AlarmReceiver`), even though the real cause was purely about ordering inside the Service itself. The fix: `startForeground()` is now the literal first real action, using a minimal notification that needs no database/SharedPreferences access at all, with everything heavier deferred until after the foreground promise is fulfilled.

**Why the old pop-up safety alarm was removed entirely.** It used to fire a full-screen, sound-and-vibration "are you OK, tap to confirm" check every 15-25 minutes, all day. Users found it more disruptive than reassuring — a genuine UX complaint, not a technical one — and it was also, indirectly, the trigger for the crash above (it's what restarted the service via `AlarmReceiver`). The replacement is a **silent health check** every 20 minutes: it runs the exact same underlying checks (GPS on? internet? battery-optimization exempted? notifications enabled? unsynced queue too big? has GPS lock gone stale?) but with zero UI — no sound, no vibration, no interruption — and only writes a record to the server **if something is actually wrong.** This is a good general pattern: if you only need to *know about* a problem, you don't need to *interrupt a human* about it — log it and let the exception reporting do the alerting.

**Why the "poison row" self-heal exists.** A single malformed record — most often a crash-report record whose `textMsg` contained a full stack trace, longer than the server's database column allows — would get rejected by the server's `INSERT` on *every single sync attempt, forever*, because the app kept retrying the exact same oversized value. Worse, on newer PHP (8.1+), `mysqli` throws an exception on a bad `INSERT` by default rather than gracefully returning `false`, which meant one bad row could crash the *entire batch's* HTTP response with a 500 error — taking down the 9 good records sitting next to it in the same batch too. The fix has two halves: server-side, `mysqli_report(MYSQLI_REPORT_OFF)` restores the old "return false, don't throw" behavior so one bad row degrades gracefully instead of nuking the whole batch; client-side, `textMsg` now gets hard-capped to 250 characters *at the point of construction* (so new records can never trigger this again), and any *already-stuck* record gets its `textMsg` trimmed in-place the next time a sync confirms the server didn't accept it — a genuinely self-healing loop, with no manual database surgery required.

**Why `TEXT_MSG_MAX_LEN` lives in the app instead of just widening the server's database column.** Widening the column (`TEXT` instead of `VARCHAR(255)`) would also have worked and was considered — but capping client-side is strictly cheaper and safer: it needs no server deploy, it protects against *any* future column-size mismatch (not just this one), and it keeps the payload small regardless, which matters on slow field connections. Both were still recommended as complementary (belt-and-suspenders), not either/or.

**Why Room uses `fallbackToDestructiveMigration()` — and the real constraint it creates.** This tells Room "if the database schema version changes and you don't have an explicit migration path, just wipe the local database and start fresh" — which is a completely reasonable shortcut *until you remember this database holds unsynced data*. It directly ruled out the "obvious" fix of just adding a proper `accuracy` column to the table — bumping the schema version would delete every field phone's unsynced records the next time they update the app. That's why fix accuracy ended up riding along inside the existing free-text `textMsg` column instead of a real typed column: it was the only way to add the diagnostic without a single field device losing data.

**Why the sync batch size was reduced from 20 down to 10.** Smaller batches mean a poison row (see above) blocks fewer good records behind it while its self-heal kicks in, and each individual HTTP request stays smaller and faster on a weak connection — a direct trade of slightly more total requests for lower blast-radius per request.

**Why Gmail got replaced with Brevo.** Gmail is built to send mail *from one person, from roughly one place*. This server sends mail programmatically, and Gmail's abuse detection twice treated that as suspicious — first blocking the login outright (`534-5.7.9`), and later (after credentials were rotated) simply refusing to authenticate at all. Brevo is a "transactional email" provider — its entire business is "let servers send automated email reliably" — which is exactly the right tool for the job Gmail was never meant to do.

**Why there are three independent restart mechanisms instead of one.** Covered in section 5.8 — the honest reasoning is that the cost of over-engineering redundancy here (a few extra lines of scheduling code) is trivially small compared to the cost of silently losing an entire day's tracking data because one single restart path had a gap.

**Why "at-least-once" delivery (with server-side dedup) instead of trying for exactly-once.** It's much harder to build a networked system that guarantees a message arrives *exactly* once than one that guarantees it arrives *at least* once — exactly-once generally requires either a persistent connection with acknowledgments, or complex distributed transaction logic. This app takes the pragmatic, much simpler path: the phone will happily re-send a record if it's not 100% sure the server got it (e.g., the insert succeeded but the confirmation response got lost on a flaky connection), and the server is responsible for recognizing and gracefully handling the duplicate via a `(imei, RecNo, sessionId)` lookup before inserting. Simpler to build, simpler to reason about, and the failure mode (a very rare harmless duplicate check) is far cheaper than the failure mode of the alternative (a genuinely lost record).

---

## 9. What this project genuinely cannot do (be honest about this)

An interviewer respects "here's what I knowingly traded off" far more than a project that claims to have no weaknesses.

- **It is not real-time.** The office dashboard is always looking at data that's up to ~2 minutes (plus jitter) old, because everything goes through the polling sync loop, not a live push connection. If someone needs true live tracking (watching a dot move on a map *right now*), this architecture doesn't provide that.
- **Passwords aren't hashed.** `User.password` is stored and compared as plain text in the local SQLite database (`validateUser(mobile, password)` does a direct equality check). Anyone with access to the phone's file system (or a rooted device) could read it directly. This is a real, fixable gap — the standard fix is hashing with something like bcrypt before storing, and never storing or comparing the raw password at all.
- **The local database is unencrypted.** Room/SQLite here stores everything, including location history, in plain readable form on disk. A rooted or physically-compromised device exposes that data. (Room does support encryption via SQLCipher, but this project doesn't use it.)
- **`fallbackToDestructiveMigration()` is a live data-loss trap.** Any future schema change that bumps the database version will silently wipe every field device's local, unsynced data on update, with zero warning to the user. This is a deliberate short-term trade (see section 8) but it's a ticking time bomb for any *future* schema change unless it's addressed with a real migration path.
- **No automated tests.** There's no visible unit test suite or instrumentation test suite exercising the filtering logic, the sync logic, or the DAOs. Correctness today rests entirely on manual field-testing and reading logcat output after the fact — which is exactly how several of the bugs in section 8 were originally found, days or weeks after they started happening.
- **The core server code isn't in this repository at all.** `sync_locations.php`, `verify_activation.php`, `check_mobile.php` and the underlying database schema live only on the production server, edited directly over SSH. There's no version control, no rollback path, no code review, and no local copy for a new developer to even read — a real risk if the server were ever lost or a bad edit needed to be reverted.
- **Location accuracy is fundamentally out of the app's control.** No amount of clever filtering logic can conjure a GPS satellite signal a phone genuinely can't receive (deep indoors, certain hardware faults, some cheaper phones). The app's job in that situation is limited to *honestly reporting* that the fix is low quality (the `Acc:`/`Gnss:` tags) — it cannot make a bad fix good.
- **One account = one active session model.** A mobile number is effectively the user's whole identity; there's no concept of a worker being logged in on two devices at once, or of individually-scoped roles/permissions beyond "logged in or not." That's fine for a single-device field-worker use case, but it wouldn't extend cleanly to, say, a supervisor role with different app behavior.
- **No offline map tiles.** `MapViewActivity`/the dashboard map both need internet to render Google Maps tiles at all — even though the *tracking* itself works fully offline, *viewing* a route on a map does not.
- **Photo/video compression is one-size-fits-all.** Every photo gets squeezed to roughly the same ~200KB/720×1280 target regardless of what it's a photo *of* — fine for a general "proof of location" snapshot, potentially too lossy if a photo ever needs to serve as detailed evidence of something specific.
- **The `RecNo` counter has edge cases around app data being cleared.** It's meant to be a strictly increasing per-user record counter, persisted primarily in SharedPreferences with a database fallback — but "clear app data" or a fresh reinstall resets that counter, and while the code has fallback logic to recover it from the DB's max existing `RecNo`, a genuinely empty/cleared local database on a phone with old synced-and-deleted history could, in principle, restart numbering from a point that collides with old server-side records for that same mobile number.

---

## 10. Interview-answer versions (short, sayable out loud)

**"Walk me through the architecture."**
"It's an Android foreground service that keeps polling GPS every ten seconds, runs each fix through a filtering layer that decides whether it's worth saving — based on distance moved, fix accuracy, and a plausibility check on implied speed — and saves the ones that pass into a local Room database. Separately, every couple of minutes, whatever hasn't been synced yet gets batched up and POSTed to a PHP backend, which dedupes and inserts into MySQL. Everything's designed around 'never lose data because the network dropped' — the local save always happens first, the network sync is a separate, retryable concern layered on top."

**"How do you handle unreliable connectivity?"**
"Store-first, sync-later. Nothing about saving a location depends on having internet. The sync loop just checks 'is there anything unsent right now,' and if the request fails, it just tries again on the next cycle — the unsynced rows just sit safely in the local database until they succeed. I also added dedup on the server side, keyed on record number plus session ID, specifically so it's safe for the app to occasionally re-send something it's not 100% sure got through."

**"Why Room instead of just talking to the server directly?"**
"Because you can't guarantee the network's there when a GPS point comes in — you can basically always write to local disk. Room's the durable buffer between 'a location just happened' and 'the server has heard about it,' and those two things need to be decoupled."

**"What was the hardest bug you dealt with?"**
"A silent tracking outage. A phone that lost GPS lock kept producing low-accuracy fixes, and because of how I'd chained my save-decision logic with `else if`, rejecting one of those fixes was accidentally skipping the time-based fallback checks too — so tracking just went dark for hours with zero error, zero log noise, nothing. I only found it by reading raw logcat off the affected device line by line. The fix was making the fallback checks structurally independent from the distance-check branch, so a rejection in one path can never suppress a different path."

**"How do you prevent duplicate records?"**
"Every record carries a per-user record number plus the session ID it belongs to. Before inserting, the server checks whether that exact combination already exists. That lets the client safely re-send something it's unsure about — worst case, the server just says 'already got that one' instead of creating a duplicate row."

**"Why not push notifications / a live connection instead of polling?"**
"Battery and simplicity, mainly. A persistent connection (or FCM-driven live updates) would need to survive Doze mode, network switches, and background restrictions all day, every day, on hundreds of different Android OEMs — that's a lot of additional failure surface for a use case that genuinely doesn't need sub-minute freshness. Polling every couple of minutes is simpler to reason about, cheaper on battery, and good enough for what this actually needs to do."

**"What would you do differently if you rewrote this today?"**
"Hash passwords properly instead of storing them plain. Put the server-side PHP and the database schema into version control instead of editing live over SSH with no rollback. Add at least basic instrumentation tests around the location-filtering logic specifically, since that's the piece with the most subtle bugs and the least visibility when it breaks. And I'd design a real Room migration path from day one instead of leaning on destructive fallback, because that decision paints you into a corner the longer the app's been in the field."

**"What's the biggest trade-off you made on purpose?"**
"Redundant restart safety over elegance. There are three separate mechanisms that can restart the tracking service — `START_STICKY`, an immediate alarm on service destroy, and a 15-minute WorkManager watchdog. That's overlapping and a little inelegant, but the cost of a gap in that coverage is an entire day of lost field data, so I'd rather have three slightly redundant safety nets than one clean one with a hole in it."

---

## 11. Glossary

| Term | Plain-English meaning |
|---|---|
| **Activity** | One screen of an Android app. |
| **Service** | Android component that runs code with no screen, potentially in the background. |
| **Foreground Service** | A Service that's required to show a permanent notification so the OS won't kill it as aggressively. |
| **BroadcastReceiver** | Code that wakes up only when the system (or your own app) announces a specific event. |
| **WorkManager / Worker** | Google's library for scheduling background jobs that should eventually run, surviving reboots. |
| **Room** | Google's library that lets you use a real SQLite database from Java/Kotlin without hand-writing SQL boilerplate. |
| **Entity** | A Java class that represents one database table, via Room annotations. |
| **DAO (Data Access Object)** | An interface where you declare SQL queries as annotations; Room generates the real code. |
| **SQLite** | A tiny, file-based, real SQL database — no separate server needed, the whole DB is one file. |
| **SharedPreferences** | Android's simple key-value settings storage, persists across app restarts. |
| **FusedLocationProviderClient** | Google's API that blends GPS, Wi-Fi, and cell-tower signals into one location estimate. |
| **GNSS** | "Global Navigation Satellite System" — the general term for GPS and equivalents (GLONASS, Galileo, etc). |
| **Accuracy (in a Location object)** | Android's own confidence radius, in meters — smaller number = more trustworthy fix. |
| **Doze mode / App Standby** | Android's background battery-saving system that delays/kills inactive apps. |
| **Battery optimization exemption** | A special permission that turns off Doze-style restrictions for one specific app. |
| **Wake lock** | A flag telling Android "don't let the CPU fully sleep right now," used briefly to guarantee background work completes. |
| **HTTP POST** | A network request that sends data to a server (as opposed to GET, which just asks for data). |
| **Multipart form-data** | The HTTP format used to upload a file (like a CSV or a photo) alongside regular text fields. |
| **JSON** | A plain-text data format (`{"key": "value"}`) used almost everywhere for API requests/responses. |
| **Polling** | Repeatedly asking "is there anything new?" on a timer, instead of being told immediately when something changes. |
| **REST / REST-ish API** | A style of web API built around simple HTTP requests to specific URLs, usually returning JSON. |
| **SMTP** | The protocol actually used to send email (as opposed to reading it). |
| **PHPMailer** | A popular PHP library that handles sending email through SMTP reliably. |
| **Transactional email provider** | An email service (like Brevo/SendGrid) built specifically for apps/servers to send automated email reliably, as opposed to a personal inbox like Gmail. |
| **IP authorization (Brevo)** | A security setting that only allows SMTP login from specific, pre-approved server IP addresses. |
| **mysqli** | PHP's library for talking to a MySQL database. |
| **NTP (Network Time Protocol)** | How computers fetch the correct, tamper-proof time from dedicated internet time servers. |
| **elapsedRealtime()** | An Android clock that measures time since the device booted — can't be faked by changing the date in Settings. |
| **Mock location** | A fake GPS position injected by a "fake GPS" app, used to cheat location-based systems; Android can flag when this happens. |
| **Dedup (deduplication)** | Detecting and ignoring a record that's already been received, to avoid storing it twice. |
| **At-least-once delivery** | A reliability model where a message might occasionally be sent more than once, but is guaranteed to arrive at least once — simpler to build than "exactly-once," as long as the receiver can detect and ignore duplicates. |
| **Batch (in syncing)** | Sending several records together in one network request instead of one request per record. |
| **Poison row / poison message** | A single bad piece of data that fails every time it's retried, potentially blocking everything queued behind it. |
| **Foreground/background thread** | The main thread runs the UI and must never be blocked; any real work (database, network) has to run on a separate background thread. |
| **ExecutorService** | A Java tool for running background work on a managed pool of threads instead of creating raw `Thread` objects by hand. |
| **CountDownLatch** | A Java concurrency tool that lets one thread wait until another thread signals "I'm done" — used here to make an async network call behave like a blocking one when needed. |
| **CameraX** | Google's modern, higher-level camera library for Android, built to smooth over the huge inconsistency in raw camera hardware across manufacturers. |
| **EXIF data** | Metadata embedded in a photo file (like camera orientation) that needs to be preserved or corrected when you resize/compress an image. |
| **ProGuard / R8 (minify/shrink)** | Tools that shrink and obfuscate your compiled app's code for release builds, which is why release builds sometimes need explicit "keep this class" rules for code accessed only via reflection (like Room entities). |
| **versionCode / versionName** | Android's two version numbers — `versionCode` is an integer the Play Store uses internally to know which build is newer (must always increase); `versionName` is the human-readable string like "4.4" shown to users. |
| **Foreground service type** | A required declaration (`android:foregroundServiceType="location"`) telling Android *why* a foreground service is allowed to keep running — newer Android versions increasingly restrict which types are even allowed to exist. |

---

*Document generated from a direct read of the ROU Track codebase at commit `7bb03e5` / working tree v4.4 (versionCode 5). Every code detail, log line, and constant referenced above (the 50m/35m/150km/h/10-minute/15-minute thresholds, the real accuracy values from the field logs, the exact PHP error text, the SMTP failure sequence) is taken directly from the actual source and the real debugging session that shaped it — nothing here is invented or generic.*
