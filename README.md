# WidgetRelay

**Read and click Android home screen widgets from [Tasker](https://tasker.joaoapps.com/).**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%206.0%2B-brightgreen.svg)](#requirements)

Lots of apps show information in a widget that they expose nowhere else — no API,
no notification, no content provider. WidgetRelay hosts those widgets invisibly
in the background, extracts their contents, and hands them to Tasker as plain
text or JSON. It can also click elements inside them, so a widget button becomes
something a task can trigger.

Typical uses:

- Read a value that only exists in a widget (unread counts, a sensor reading,
  the next departure, a balance, "now playing")
- Poll a widget on a schedule and act when the value changes
- Press a widget's button from a task (refresh, play/pause, toggle)

---

## Table of contents

- [Actions](#actions)
- [Requirements](#requirements)
- [Setup](#setup)
- [Building](#building)
- [How it works](#how-it-works)
- [Implementation notes (the hacks)](#implementation-notes-the-hacks)
- [Limitations](#limitations)
- [License](#license)

---

## Actions

WidgetRelay registers four Tasker plugin actions. All of them are configured the
same way: pick an app and widget, choose a size, and — for all but the JSON
action — tap the element you want in the extracted-data list.

| Action | Input | Output |
| --- | --- | --- |
| **Get Widget Data** | element path | `%widget_value` — the element's text, content description or resource id |
| **Get Widget JSON** | — | `%widget_json` — the whole widget as a JSON tree |
| **Click Widget Path** | element path | — |
| **Click Widget Text** | element text | — |

### Addressing elements

Elements are addressed by their **path** in the widget's view tree: `/root` is
the widget itself, `/root/0/2` is the third child of the first child, and so on.
The configuration screen shows every extracted element with its path, so you
normally never type one by hand — you tap the element and the path is filled in.

Paths are stable as long as the widget's layout doesn't change. If a widget
rearranges itself (many do, depending on state or size), prefer **Click Widget
Text**, which searches by the visible text or content description instead. Both
input fields accept Tasker variables, so `%par1` or `%text` work too.

### JSON output

**Get Widget JSON** returns the widget as a nested tree. `type` is the view
class, `children` is only present when a node has any:

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
            { "type": "TextView", "text": "2 ungelesene Nachrichten" }
          ]
        },
        { "type": "ImageView", "description": "Neuer Chat" }
      ]
    }
  ]
}
```

Parse it in Tasker with the **Java Function** action, `JSON Read`, or by using
the JSON path support in newer Tasker versions.

---

## Requirements

- Android 6.0 (API 23) or newer
- [Tasker](https://tasker.joaoapps.com/) (or another Locale/Tasker plugin host)
- Two permissions, both requested during action configuration:
  - **Bind widget** — a system dialog shown the first time you pick a widget
  - **Display over other apps** — needed so widgets load their contents while
    Tasker runs the action in the background ([why](#2-widgets-only-load-their-content-in-a-real-window))

WidgetRelay has no launcher icon and no UI of its own — everything happens
inside the Tasker action configuration screen.

## Setup

1. Install WidgetRelay.
2. In Tasker, add an action: **Plugin → WidgetRelay → _(pick one of the four)_**.
3. The widget picker opens immediately. Search for the app or widget you want.
4. Grant the widget bind permission when the system asks.
5. Choose the widget size in cells. **This matters** — many widgets render a
   different layout at different sizes, so pick the size you actually want to
   read.
6. Tap the element you want in the extracted-data list below the preview.
   For the click actions, elements that can't be clicked are dimmed and can't be
   selected.
7. Save. On first save you'll be sent to Android's "Display over other apps"
   settings if that permission is still missing.

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

`assembleRelease` produces an unsigned APK unless you provide a keystore.
Create one (once — and back it up, an APK signed with a different key cannot
replace an installed one):

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

Both the keystore and that file are gitignored. With them in place,
`./gradlew assembleRelease` writes a signed APK to
`app/build/outputs/apk/release/`.

---

## How it works

```
app/src/main/java/com/cennoxx/widgetrelay/
├── widget/
│   ├── WidgetHost.kt                     AppWidgetHost singleton: allocate, bind, create views
│   ├── WidgetExtractor.kt                walks the inflated view tree into WidgetNodes
│   ├── WidgetNode.kt                     one extracted element
│   ├── WidgetGrid.kt                     launcher cell geometry (see hack 6)
│   ├── WidgetListModels.kt               picker models + default span calculation
│   ├── ActivityWidgetSelector.kt         searchable widget picker
│   └── WidgetExpandableAdapter.kt        picker list, grouped by app
└── tasker/widgets/
    ├── WidgetActionInput.kt              shared plugin input (widget id, size, query)
    ├── WidgetActionRuntime.kt            the runtime core: attach, poll, extract, click
    ├── ActivityConfigWidgetActionBase.kt shared configuration UI
    ├── GetWidgetData.kt                  ─┐
    ├── GetWidgetJson.kt                   │ the four actions:
    ├── ClickWidget.kt                     │ runner + config helper + config activity
    └── ClickWidgetText.kt                ─┘
```

The flow is the same for every action:

1. At **configuration** time a widget is bound and its `appWidgetId` is stored in
   the Tasker action. The binding is persistent, so it survives reboots.
2. At **run** time the runner recreates that widget off-screen, waits for the
   provider to deliver its `RemoteViews`, and then reads or clicks the view tree.

---

## Implementation notes (the hacks)

Android has no supported way for an app to read what a widget displays, and no
way at all to click one. Everything below exists to work around that. If you're
here to fix a bug or port the idea, read this section first.

### 1. Hosting widgets outside a launcher

`AppWidgetHost` is the API launchers use. Nothing stops a normal app from
creating one, but the widget lifecycle has to be managed by hand:

- `allocateAppWidgetId()` reserves an id, `bindAppWidgetIdIfAllowed()` binds a
  provider to it. That call fails unless the user granted the bind permission,
  so the failure path launches `ACTION_APPWIDGET_BIND` and lets the system ask.
- Some widgets have their own configuration activity, which must run through
  `startAppWidgetConfigureActivityForResult()` — going through a plain intent
  fails for providers whose config activity isn't exported.
- Allocated ids are a **leaked system resource** if you forget them. The config
  activity deletes the id again if you cancel, pick a different widget, or
  replace the widget of an existing action.
- `QUERY_ALL_PACKAGES` is needed for `getInstalledProviders()` to return widgets
  from every installed app instead of a small subset.

*(`WidgetHost.kt`, `ActivityConfigWidgetActionBase.kt`)*

### 2. Widgets only load their content in a real window

**This is the central hack.** A widget's `ListView`, `GridView` or `StackView`
does not carry its rows in the `RemoteViews`. It connects to the provider's
`RemoteViewsService` and loads them asynchronously — but only once the host view
is **attached to a window and laid out**.

The first implementation inflated the widget completely detached. That works in
the configuration screen (the preview sits in a real activity window), which is
exactly why it was so confusing: every list row was visible while configuring,
and gone when Tasker ran the action.

The fix is to give the widget a real window at runtime: it's added to a
`TYPE_APPLICATION_OVERLAY` window that is sized like a home screen widget but
`alpha = 0`, `FLAG_NOT_TOUCHABLE` and `FLAG_NOT_FOCUSABLE`, then removed as soon
as the action is done. That's what the "Display over other apps" permission is
for — an app cannot add a window from the background without it.

*(`WidgetActionRuntime.withAttachedWidget()`)*

### 3. Polling, because there is no "widget is ready" callback

Nothing tells you when a provider has finished delivering its views, and
collection rows arrive later still. So the runtime polls the tree every 100 ms
starting 50 ms after attaching, up to an 8 s timeout, with two different exit
conditions:

- **Path and text actions** stop the moment the element they need exists. That
  usually happens on the first or second poll, so those actions are fast.
- **Get Widget JSON** needs everything, so it waits for the tree to *settle*:
  unchanged for 500 ms, and never finishing earlier than 800 ms after attach.
  Without that lower bound, two identical polls taken before a list service has
  even connected would look "stable" and the capture would come back empty.

*(`WidgetActionRuntime.captureNodes()`)*

### 4. Clicking: walk up, and go through the AdapterView

`RemoteViews` attaches its `PendingIntent` wherever the widget's author put it,
which is almost never the `TextView` you actually want to click — it's usually a
row or container several levels up. So a click walks up from the target view
until some ancestor both has a click listener and consumes `performClick()`.

Rows of a collection are different again: they are clicked through the
`AdapterView`, which merges the row's *fill-in intent* into the provider's
`PendingIntent` template. Calling `performClick()` on such a row does nothing, so
when the walk-up reaches an `AdapterView` it calls `performItemClick()` instead.

*(`WidgetActionRuntime.performClickOn()`)*

### 5. Detecting what is clickable at all

The configuration screen dims elements that can't be clicked. Clickability is
computed while extracting, using the *same* rule the click walk-up uses, so what
looks selectable is exactly what the runner can actually click: a node counts as
clickable if it has a click listener itself, inherits one from an ancestor, or
its parent is an `AdapterView`. The flag is carried down the traversal queue
rather than looked up per node, so it can't drift out of sync.

*(`WidgetExtractor.extractFromRemoteViews()`)*

### 6. Guessing the launcher's grid

Widgets pick their layout based on the size they're given, so the configuration
preview and the background runtime have to agree on how big "2 x 2" is — if they
disagree, you configure against one layout and read another. There is no API for
the launcher's cell size, so it's approximated: 5 columns across the usable
screen width, cells 1.4× as tall as they are wide. `updateAppWidgetSize()` then
tells the provider which size to render for.

The math lives in one place so the preview and runtime cannot drift apart.

*(`WidgetGrid.kt`)*

### 7. Main thread vs. Tasker's background thread

Tasker runs plugin actions on a background thread, but everything involving
`AppWidgetHost`, windows and views must happen on the main thread. Each action
therefore posts its work to a main-thread `Handler` and blocks on a
`CountDownLatch` until the polling loop finishes or the timeout expires — the
runner stays synchronous, which is what Tasker expects, while all view work
happens where Android requires it.

*(`WidgetActionRuntime.withAttachedWidget()`)*

### 8. Reading the tree at all

There's no public way to inspect a `RemoteViews` object's contents. The widget
has to be inflated into a real `AppWidgetHostView` and the resulting **view
tree** walked instead: class name, `TextView.getText()`, content description, and
resource id name, with a breadth-first walk producing the `/root/0/1` paths.

That's also why extraction is deliberately shallow — text and content
description are the only things a widget reliably exposes to a host.

*(`WidgetExtractor.kt`)*

### 9. Small ones

- The configuration screen opens the widget picker **immediately** when adding a
  new action, so there's no empty config screen flashing past first.
- Tasker's generated action blurbs are disabled for every input field
  (`ignoreInStringBlurb`) and written by hand instead, so the action list shows
  something readable.
- The widget preview is only measured after a layout pass — sizing it before
  that gives a zero-width frame and a wrongly rendered widget.

---

## Limitations

- **Background activity launch restrictions.** Widget buttons that fire a
  `PendingIntent` for a *broadcast* or *service* (refresh, play/pause, toggles)
  work fine. Ones that open an *activity* may be blocked by Android when the
  click comes from the background.
- **Timing.** A widget that needs longer than 8 s to deliver its content will
  time out; a list that delivers its first rows more than 500 ms after the static
  layout can still be captured empty by the JSON action. Both bounds are
  constants at the top of `WidgetActionRuntime.kt`.
- **Paths are brittle.** Widgets that rearrange their layout invalidate stored
  paths. Use Click Widget Text where possible.
- **One element per read.** Get Widget Data returns a single value; use Get
  Widget JSON if you need several.
- **No conditions or events.** WidgetRelay only provides actions — polling with a
  Tasker profile is the way to react to changes.
- Widgets are re-created on every run, so this is not free: expect a run to take
  a few hundred milliseconds.

---

## License

WidgetRelay is licensed under the **GNU General Public License v3.0** — see
[LICENSE](LICENSE).

The project is a fork of
[joaomgcd/TaskerPluginSample](https://github.com/joaomgcd/TaskerPluginSample),
whose Tasker plugin library is GPL-3.0 licensed and is compiled into this app.

See [NOTICE](NOTICE) for third-party attribution and the list of changes made to
the upstream project.

### Credits

- [João Dias (joaomgcd)](https://github.com/joaomgcd) for the Tasker plugin
  library this is built on
- Pent (dinglisch) for Tasker and its
  [plugin protocol](https://tasker.joaoapps.com/plugins.html)
