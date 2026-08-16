# WidgetRelay

**Read and click Android home screen widgets from [Tasker](https://tasker.joaoapps.com/).**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-brightgreen.svg)](#requirements)

Lots of apps show information in a widget that they expose nowhere else — no API, no notification, no content provider. WidgetRelay hosts those widgets invisibly in the background, extracts their contents, and hands them to Tasker as plain text or JSON. It can also click elements inside them, so a widget button becomes something a task can trigger, and it can watch a widget and fire a Tasker event the moment its contents change.

Typical uses:

- Read a value that only exists in a widget (unread counts, a sensor reading, the next departure, a balance, "now playing")
- React the moment a widget changes, without polling
- Press a widget's button from a task (refresh, play/pause, toggle)

---

## Table of contents

- [Actions](#actions)
- [The event](#the-event)
- [Requirements](#requirements)
- [Setup](#setup)
- [Building](#building)
- [How it works](#how-it-works)
- [Implementation notes (the hacks)](#implementation-notes-the-hacks)
- [Limitations](#limitations)
- [License](#license)

---

## Actions

WidgetRelay registers four Tasker plugin actions (and one [event](#the-event)). The two that touch a widget are configured the same way: pick an app and widget, choose a size, and tap the element you want in the extracted-data list or enter an selector.

| Action | Input | Output |
| --- | --- | --- |
| **Get Widget Data** | element path / regex | `%widget_value` — the element's text, content description or resource id |
| **Get Widget Data** | *(empty path)* | `%widget_json` — the whole widget as a JSON tree |
| **Click Widget** | element path / text / regex | — |
| **Pause Watching Widgets** | pause / resume / toggle | `%widget_setting_state` — the resulting state |
| **Keep the CPU Awake** | on / off / toggle | `%widget_setting_state` — the resulting state |

**Get Widget Data** covers both reading modes: give it an element path and it returns that one value, leave the path empty and it returns everything as JSON. The empty case is also slower on purpose — see [hack 3](#3-polling-because-there-is-no-widget-is-ready-callback).

### Controlling the monitor from a task

The last two actions control the [widget monitor](#why-this-needs-a-running-app) rather than reading anything, and are the same two switches the app's own screen offers — so a task can do what you would otherwise do by hand:

- **Pause Watching Widgets** stops the monitor without forgetting which widgets it watches, so resuming picks them all up again. Useful to keep it off while you don't need events, or around something that would otherwise fight over the widgets.
- **Keep the CPU Awake** switches the monitor's optional wake lock. Since it costs real battery, a task can turn it on only for the hours you actually need changes caught during Doze, and off again afterwards.

Both take **on**, **off** or **toggle**, and both accept a Tasker variable instead (`%state`, also understanding `true`/`false`, `yes`/`no`, `1`/`0`), so one action can serve both directions. `%widget_setting_state` reports the state they left things in, which is what you want after a toggle.

### Addressing elements

Elements are addressed by their **path** in the widget's view tree: `/root` is the widget itself, `/root/0/2` is the third child of the first child, and so on. The configuration screen shows every extracted element with its path, so you normally never type one by hand — you tap the element and the path is filled in.

**Click Widget** uses a selector: `/root/...` clicks by path; anything else matches visible text or a content description. In its configuration, tap an element to select its path, or long-press one to select its text. The selector accepts Tasker variables, so `%par1` or `%text` work too.

Text selectors also accept a regular expression, written `/pattern/flags` - `/^coffee$/i` matches "COFFEE" and "Coffee" alike. Supported flags are `i` (case-insensitive), `m` (multiline `^`/`$`) and `s` (`.` also matches newlines). It's a search, not a full match, so `/coffee/i` also matches "Buy coffee". Anything not wrapped in slashes is an exact match. Invalid patterns or flags fail with a clear error.

### JSON output

With an empty element path, **Get Widget Data** returns the widget as a nested tree in `%widget_json`. `type` is the view class, `children` is only present when a node has any:

```json
{
  "type": "AppWidgetHostView",
  "children": [
    {
      "type": "LinearLayout",
      "children": [
        { "type": "ImageView" },
        {
          "type": "LinearLayout",
          "children": [
            { "type": "TextView", "text": "WhatsApp" },
            { "type": "TextView", "text": "2 unread Messages" }
          ]
        },
        { "type": "ImageView", "description": "New Chat" }
      ]
    }
  ]
}
```

Parse it in Tasker with the **Java Function** action, `JSON Read`, or by using the JSON path support in newer Tasker versions.

---

## The event

**Widget Updated** is a Tasker *event* condition: it fires when a widget's contents change, instead of you polling one of the actions on a timer.

Configure it like the actions — pick app, widget and size. The element path is **optional** here:

- leave it empty and the event fires on *any* change in the widget
- pick an element and it only fires when that element's value changes

| Variable | Contents |
| --- | --- |
| `%widget_value` | the watched element's new value (empty if no element was picked) |
| `%widget_old_value` | its value before the update |
| `%widget_changed()` | Tasker array of the paths of every element that changed |
| `%widget_json` | the whole widget as a JSON tree, same shape as Get Widget Data's |

### Why this needs a running app

An action can create a widget, read it and throw it away in the same second. An event cannot: something has to keep the widget alive and watch it. So saving a Widget Updated event registers its widget with the **widget monitor**, a foreground service that hosts every registered widget in a permanent invisible window and reports what changed.

That is what the persistent notification is for, and it is also why the app now has a launcher icon: opening it shows the monitor screen with every widget currently being hosted, how many elements were read from it, how often it changed and when — plus the permissions background monitoring needs.

Two things are worth knowing:

- **Tasker never tells a plugin that an event was deleted.** Removing the event in Tasker leaves its widget registered here. Remove it on the monitor screen when you no longer want it hosted.
- **The monitor is only as reliable as the system lets it be.** See [hack 10](#10-staying-alive-for-the-event) for what it does about that.

---

## Requirements

- Android 6.0 (API 23) or newer
- [Tasker](https://tasker.joaoapps.com/) (or another Locale/Tasker plugin host)
- Two permissions, both requested during action configuration:
  - **Bind widget** — a system dialog shown the first time you pick a widget
  - **Display over other apps** — needed so widgets load their contents while Tasker runs the action in the background ([why](#2-widgets-only-load-their-content-in-a-real-window))
- For the **Widget Updated** event only: notifications (for the monitor's ongoing notification) and, in practice, an exemption from battery optimisation. Both are requested from the monitor screen.

Apart from the monitor screen, WidgetRelay has no UI of its own — everything else happens inside the Tasker configuration screens.

## Setup

1. Install WidgetRelay.
2. In Tasker, add an action: **Plugin → WidgetRelay → _(pick one)_**.
3. The widget picker opens immediately. Search for the app or widget you want.
4. Grant the widget bind permission when the system asks.
5. Choose the widget size in cells. **This matters** — many widgets render a different layout at different sizes, so pick the size you actually want to read.
6. Tap the element you want in the extracted-data list below the preview. Elements the chosen action probably can't use are dimmed, but stay selectable — the extraction heuristics can be wrong.
7. Save. On first save you'll be sent to Android's "Display over other apps" settings if that permission is still missing.

For the **Widget Updated** event, add it as a Tasker *event* context (**Event → Plugin → WidgetRelay → Widget Updated**) instead of an action; the element path is optional there. After saving, open WidgetRelay once and grant the permissions it lists, so the monitor survives in the background.

## Building

```bash
git clone https://github.com/CennoxX/WidgetRelay.git
```

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. To install directly:

```bash
./gradlew installDebug
```

### Signing a release build

`assembleRelease` produces an unsigned APK unless you provide a keystore. Create one (once — and back it up, an APK signed with a different key cannot replace an installed one):

```bash
keytool -genkey -v -keystore widgetrelay.jks -alias widgetrelay -keyalg RSA -keysize 2048 -validity 10000
```

Then put a `keystore.properties` in the project root:

```properties
storeFile=widgetrelay.jks
storePassword=<the password you chose>
keyAlias=widgetrelay
keyPassword=<the key password you chose>
```

Both the keystore and that file are gitignored. With them in place, `./gradlew assembleRelease` writes a signed APK to `app/build/outputs/apk/release/`.

---

## How it works

```
app/src/main/java/com/cennoxx/widgetrelay/
├── widget/
│   ├── WidgetHost.kt                     AppWidgetHost singleton: allocate, bind, create views
│   ├── WidgetExtractor.kt                walks the inflated view tree into WidgetNodes
│   ├── WidgetNode.kt                     one extracted element
│   ├── WidgetGrid.kt                     launcher cell geometry (see hack 6)
│   ├── WidgetJson.kt                     nodes -> JSON tree / path-value map
│   ├── WidgetListModels.kt               picker models + default span calculation
│   ├── ActivityWidgetSelector.kt         searchable widget picker
│   ├── WidgetExpandableAdapter.kt        picker list, grouped by app
│   ├── WidgetMonitorRegistry.kt          which widgets to keep hosted (persisted)
│   ├── WidgetMonitorService.kt           hosts them, detects changes, fires events
│   ├── WidgetMonitorWatchdog.kt          boot / update / alarm restarts
│   └── ActivityWidgetMonitor.kt          the app's only screen: live monitor state
└── tasker/widgets/
    ├── WidgetActionInput.kt              shared plugin input (widget id, size, query)
    ├── WidgetActionRuntime.kt            the runtime core: attach, poll, extract, click
    ├── ActivityConfigWidgetActionBase.kt shared configuration UI
    ├── WidgetRebindNotifier.kt           tells the user when a binding was lost
    ├── TextQuery.kt                      literal or /regex/ selector matching
    ├── GetWidgetData.kt                  ─┐ the widget actions:
    ├── ClickWidget.kt                    ─┘ runner + config helper + config activity
    ├── MonitorSettings.kt                 pause / wake lock actions + their config
    └── WidgetUpdated.kt                   the event: same shape, condition runner
```

The flow is the same for every action:

1. At **configuration** time a widget is bound and its `appWidgetId` is stored in the Tasker action. The binding is persistent, so it survives reboots.
2. At **run** time the runner recreates that widget off-screen, waits for the provider to deliver its `RemoteViews`, and then reads or clicks the view tree.

The event inverts step 2: instead of re-creating the widget per run, the monitor service keeps it created and pushes to Tasker when it changes.

---

## Implementation notes (the hacks)

Android has no supported way for an app to read what a widget displays, and no way at all to click one. Everything below exists to work around that. If you're here to fix a bug or port the idea, read this section first.

### 1. Hosting widgets outside a launcher

`AppWidgetHost` is the API launchers use. Nothing stops a normal app from creating one, but the widget lifecycle has to be managed by hand:

- `allocateAppWidgetId()` reserves an id, `bindAppWidgetIdIfAllowed()` binds a provider to it. That call fails unless the user granted the bind permission, so the failure path launches `ACTION_APPWIDGET_BIND` and lets the system ask.
- Some widgets have their own configuration activity, which must run through `startAppWidgetConfigureActivityForResult()` — going through a plain intent fails for providers whose config activity isn't exported.
- Allocated ids are a **leaked system resource** if you forget them. The config activity deletes the id again if you cancel, pick a different widget, or replace the widget of an existing action.
- `QUERY_ALL_PACKAGES` is needed for `getInstalledProviders()` to return widgets from every installed app instead of a small subset.

*(`WidgetHost.kt`, `ActivityConfigWidgetActionBase.kt`)*

### 2. Widgets only load their content in a real window

**This is the central hack.** A widget's `ListView`, `GridView` or `StackView` does not carry its rows in the `RemoteViews`. It connects to the provider's `RemoteViewsService` and loads them asynchronously — but only once the host view is **attached to a window and laid out**.

This is to give the widget a real window at runtime: it's added to a `TYPE_APPLICATION_OVERLAY` window that is sized like a home screen widget but `alpha = 0`, `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`, then removed as soon as the action is done. That's what the "Display over other apps" permission is for — an app cannot add a window from the background without it.

*(`WidgetActionRuntime.withAttachedWidget()`)*

### 3. Polling, because there is no "widget is ready" callback

Nothing tells you when a provider has finished delivering its views, and collection rows arrive later still. So the runtime polls the tree every 100 ms starting 50 ms after attaching, up to an 8 s timeout, with two different exit conditions:

- **Anything addressing one element** — the click actions, and Get Widget Data with a path — stops the moment that element exists. That usually happens on the first or second poll, so those runs are fast.
- **Get Widget Data without a path** needs everything, so it waits for the tree to *settle*: unchanged for 500 ms, and never finishing earlier than 800 ms after attach. Without that lower bound, two identical polls taken before a list service has even connected would look "stable" and the capture would come back empty.

This is why the same action behaves so differently with and without a path: they are two capture strategies, not two output formats.

*(`WidgetActionRuntime.captureNodes()`)*

### 4. Clicking: walk up, and go through the AdapterView

`RemoteViews` attaches its `PendingIntent` wherever the widget's author put it, which is almost never the `TextView` you actually want to click — it's usually a row or container several levels up. So a click walks up from the target view until some ancestor both has a click listener and consumes `performClick()`.

Rows of a collection are different again: they are clicked through the `AdapterView`, which merges the row's *fill-in intent* into the provider's `PendingIntent` template. Calling `performClick()` on such a row does nothing, so when the walk-up reaches an `AdapterView` it calls `performItemClick()` instead.

*(`WidgetActionRuntime.performClickOn()`)*

### 5. Detecting what is clickable at all

The configuration screen dims elements that can't be clicked. Clickability is computed while extracting, using the *same* rule the click walk-up uses, so what looks selectable is exactly what the runner can actually click: a node counts as clickable if it has a click listener itself, inherits one from an ancestor, or its parent is an `AdapterView`. The flag is carried down the traversal queue rather than looked up per node, so it can't drift out of sync.

*(`WidgetExtractor.extractFromRemoteViews()`)*

### 6. Guessing the launcher's grid

Widgets pick their layout based on the size they're given, so the configuration preview and the background runtime have to agree on how big "2 x 2" is — if they disagree, you configure against one layout and read another. There is no API for the launcher's cell size, so it's approximated: 5 columns across the usable screen width, cells 1.4× as tall as they are wide. `updateAppWidgetSize()` then tells the provider which size to render for.

The math lives in one place so the preview and runtime cannot drift apart.

*(`WidgetGrid.kt`)*

### 7. Main thread vs. Tasker's background thread

Tasker runs plugin actions on a background thread, but everything involving `AppWidgetHost`, windows and views must happen on the main thread. Each action therefore posts its work to a main-thread `Handler` and blocks on a `CountDownLatch` until the polling loop finishes or the timeout expires — the runner stays synchronous, which is what Tasker expects, while all view work happens where Android requires it.

*(`WidgetActionRuntime.withAttachedWidget()`)*

### 8. Reading the tree at all

There's no public way to inspect a `RemoteViews` object's contents. The widget has to be inflated into a real `AppWidgetHostView` and the resulting **view tree** walked instead: class name, `TextView.getText()`, content description, and resource id name, with a breadth-first walk producing the `/root/0/1` paths.

That's also why extraction is deliberately shallow — text and content description are the only things a widget reliably exposes to a host.

*(`WidgetExtractor.kt`)*

### 9. Noticing that a widget changed

There is no "widget changed" callback anywhere in the AppWidget APIs — a host is a renderer, not an observer. The only moment the system tells a host anything is when it hands it new `RemoteViews` to draw, so `AppWidgetHostView.updateAppWidget()` is overridden and that call *becomes* the change signal (`NotifyingWidgetHostView`, installed via `AppWidgetHost.onCreateView`).

That covers ordinary updates but not collections: `notifyAppWidgetViewDataChanged` goes straight to the `RemoteViewsService` and never reaches the host view, so a list can quietly repopulate with no signal at all. A slow re-capture every 15 s runs alongside the push signal to catch those.

Both paths end in the same place: extract the tree, flatten it to a `path -> value` map, and compare it with the previous one. That map is the change fingerprint — it is small, cheap to diff, and diffing it says not just *that* something changed but *which element* did, which is what lets one event watch a single value while ignoring the rest of the widget.

Matching happens in the runner, not the service: the service just tells Tasker "widget 42 changed, here is the before and after", and every enabled instance of the event decides for itself whether it cares. That is the only shape that works, because a plugin cannot enumerate its own events.

One trap worth knowing if you build on this: an `AppWidgetHost` keeps exactly **one view per appWidgetId**. Creating a second view for an id — which every plugin action and every config preview does — replaces the monitor's view in that map, and the replaced view never receives an update again. Nothing fails visibly; the event just stops firing. So both of those paths tell the monitor to re-create its view when they are done with it.

That handshake is not enough on its own, though, and assuming it was is worth a paragraph of its own. A displaced view keeps sitting in its overlay window rendering its last known state, so every health signal still looks green — the service runs, the notification counts the widget, this app's own screen says "Loaded and watching" — while the event is dead for good. And the handshake can be missed: a background service start refused under Android 12+ limits, a config activity killed before `onDestroy`, a crash in between. One missed handshake and monitoring is over until something restarts the service.

So the monitor does not trust it. `WidgetHost` tracks which view is current for each id, and every recapture tick asks whether the monitor still owns its widget — plus whether its window is even still attached. Either answer being no re-creates the view on the spot, carrying the change baseline over so the update that arrived while it was orphaned is still reported. The same check runs on sync, so opening this app repairs a stuck widget immediately instead of waiting for the tick.

The lesson generalises: a flag that records *that you once succeeded* is not a health check. `attached` was set at `addView()` and only ever cleared by our own teardown, so it could never have caught this.

*(`WidgetMonitorService`, `WidgetUpdated.kt`, `WidgetJson.kt`)*

### 10. Staying alive for the event

The actions are stateless, so nothing had to survive between runs. The event does, and Android spends a lot of effort stopping exactly that. What it does about it, roughly in order of how much it matters:

- A **foreground service** with an ongoing notification, typed `specialUse` on Android 14+. Without this the process can be killed within minutes.
- The **invisible overlay windows stay attached** for the whole time, instead of being added and removed per run. That is what keeps providers pushing updates and collection views connected — and it means the event needs the overlay permission just as much as the actions do.
- `START_STICKY` plus an `onTaskRemoved` restart, so swiping the app away or a low-memory kill comes back.
- A receiver for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, since both wipe the service and the alarm.
- A repeating **watchdog alarm** every 15 minutes as a heartbeat, in case a kill went unnoticed. Deliberately inexact — it is a safety net, not the update mechanism.
- The **condition query**, but only partly. The runner restarts the service and re-registers the widget when it is called — except the plugin library answers event queries that carry no pass-through id without ever reaching the runner, and those are exactly the ones Tasker sends on profile enable. So this repairs a lost registry entry while events still flow; it cannot resurrect a dead service, because a dead service sends no events and therefore triggers no queries. The watchdog alarm is the real safety net.
- An opt-in **partial wake lock**, off by default. Doze suspends the monitor along with the CPU, so without it updates during deep sleep are only noticed when the device wakes anyway. With it they are caught immediately, at a real battery cost — hence the toggle rather than a default.
- The monitor screen asks for an **exemption from battery optimisation**, which in practice matters more than everything above on aggressive OEM ROMs.

None of this is a guarantee. A sufficiently determined vendor ROM will still kill the service; the monitor screen exists partly so you can see whether it is actually running.

*(`WidgetMonitorService`, `WidgetMonitorWatchdog`, `ActivityWidgetMonitor`)*

---

## Limitations

- **Background activity launch restrictions.** Widget buttons that fire a `PendingIntent` for a *broadcast* or *service* (refresh, play/pause, toggles) work fine. Ones that open an *activity* may be blocked by Android when the click comes from the background.
- **Timing.** A widget that needs longer than 8 s to deliver its content will time out; a list that delivers its first rows more than 500 ms after the static layout can still be captured empty by a path-less Get Widget Data. Both bounds are constants at the top of `WidgetActionRuntime.kt`.
- **Uninstalls break widget bindings.** The system deletes a host's widget bindings when the app is uninstalled — replacing it with a differently signed build (debug vs. release) counts, normal updates don't. Actions store the widget's provider too, so reopening the action binds the same widget again automatically; just save it once more. Actions saved by versions that didn't store the provider yet have to be reconfigured once by hand. If an action *runs* against a lost binding, WidgetRelay posts a notification saying so — tapping it opens Tasker, since there is no API to jump straight to one action's edit screen; open that action and tap Save to rebind it.
- **Paths are brittle.** Widgets that rearrange their layout invalidate stored paths. Use a text selector where possible.
- **One element per read.** Get Widget Data with a path returns a single value; leave the path empty and parse `%widget_json` if you need several.
- **The event needs the app running.** A monitored widget is hosted continuously, which costs battery and a permanent notification, and the service can still be killed by aggressive power management — see [hack 10](#10-staying-alive-for-the-event). If you only need to check something occasionally, polling Get Widget Data on a timer is the more robust choice.
- **Deleted events are not noticed.** Tasker doesn't tell plugins when an event is removed, so its widget stays monitored until you remove it on the monitor screen.
- **Collection changes are found within ~15 s**, not instantly — they arrive through a path that never reaches the host view (hack 9). Ordinary widget updates are immediate.
- Widgets are re-created on every action run, so actions are not free: expect a run to take a few hundred milliseconds.

---

## License

WidgetRelay is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

The project is a fork of [joaomgcd/TaskerPluginSample](https://github.com/joaomgcd/TaskerPluginSample), whose Tasker plugin library is GPL-3.0 licensed and is compiled into this app.

See [NOTICE](NOTICE) for third-party attribution and the list of changes made to the upstream project.

### Credits

- [João Dias (joaomgcd)](https://github.com/joaomgcd) for the Tasker plugin library this is built on
- Pent (dinglisch) for Tasker and its [plugin protocol](https://tasker.joaoapps.com/plugins.html)
