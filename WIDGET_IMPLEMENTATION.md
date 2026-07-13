# WidgetRelay Implementation Guide

## Overview
WidgetRelay adds widget hosting and data extraction capabilities to the existing Tasker plugin sample app. The implementation is divided into two core modules:

1. **Widget Hosting** - manages AppWidgetHost and RemoteViews lifecycle
2. **RemoteViews Extraction** - inflates and extracts data from widgets

## Architecture

### Core Classes

#### 1. WidgetNode (Data Model)
**File:** `app/src/main/java/com/joaomgcd/taskerpluginsample/widget/WidgetNode.kt`

Represents a single extracted node from an inflated RemoteViews tree:
- `className`: The view class name (e.g., "TextView", "ImageView")
- `resourceId`: Numeric resource ID if available
- `resourceIdName`: Human-readable resource ID name
- `pathInTree`: Position path in the view hierarchy (e.g., "/root/0/1")
- `text`: Extracted text (for TextView)
- `contentDescription`: Extracted content description (for ImageView)
- `childCount`: Number of child views
- `bestValue`: Best representative value following the extraction rules

#### 2. WidgetExtractor (Extraction Logic)
**File:** `app/src/main/java/com/joaomgcd/taskerpluginsample/widget/WidgetExtractor.kt`

Handles RemoteViews inflation and traversal:
- `extractFromRemoteViews(hostView: AppWidgetHostView)`: Main entry point
  - Uses breadth-first queue traversal for reliable tree walking
  - Creates WidgetNode for each view in the hierarchy
  - Handles null safety and unknown view types gracefully

**Extraction Rules:**
1. If view is `TextView`, use its text as the best value
2. Else if view is `ImageView` with content description, use that
3. Else if view has a valid resource ID name, use that
4. Else return null for best value

#### 3. WidgetHost (Lifecycle Management)
**File:** `app/src/main/java/com/joaomgcd/taskerpluginsample/widget/WidgetHost.kt`

Manages the AppWidgetHost and RemoteViews:
- `startListening()` / `stopListening()`: Lifecycle control
- `getAvailableWidgetProviders()`: Lists all available widget providers
- `bindWidget(providerInfo)`: Binds a widget provider and creates host view
- `unbindWidget()`: Cleans up the bound widget
- `refreshExtractedData()`: Re-extracts data from current widget
- Provides `StateFlow` for reactive updates:
  - `widgetNodes`: Latest extracted nodes
  - `hostView`: Current AppWidgetHostView

**Key Design:**
- Uses Kotlin `StateFlow` for reactive updates to UI
- Thread-safe binding and cleanup
- Defensive error handling for permission denials and binding failures
- Single widget at a time (appWidgetId stored for lifecycle tracking)

#### 4. ActivityWidgetSelector (UI)
**File:** `app/src/main/java/com/joaomgcd/taskerpluginsample/widget/ActivityWidgetSelector.kt`

Demo activity for widget selection and data viewing:
- Displays list of available widget providers
- Binds selected widget to the app
- Shows the inflated widget in a FrameLayout
- Displays extracted node data in a scrollable TextView
- "Refresh Data" button for re-extraction

## Integration with Tasker Plugin Base

### Manifest Changes & Permissions
Added to `app/src/main/AndroidManifest.xml`:
- `android.permission.BIND_APPWIDGET` - required to host widgets
- `android.permission.QUERY_ALL_PACKAGES` - allows discovery of all available widgets
- `com.android.launcher.permission.INSTALL_SHORTCUT` - launcher integration
- `ActivityWidgetSelector` declaration

**Permission Handling:**
- `bindAppWidgetIdIfAllowed()` returns false if permission not yet granted
- When permission denied, app launches `AppWidgetManager.ACTION_APPWIDGET_BIND` intent
- System shows a dialog asking user to grant widget binding permission
- After user grants permission, app completes the binding via `finalizeBindingAfterPermission()`
- This two-step flow (like Smartspacer) is necessary because widget binding is protected by the system

**Widget Discovery:**
- `QUERY_ALL_PACKAGES` permission allows `AppWidgetManager.getInstalledProviders()` to discover all available widgets from all apps
- Without this permission, only a limited set of widgets is visible
- This enables discovery of widgets like Clock, Drive, Gmail, etc.

### UI Integration
- Added "Select Widget" button to ActivityMain
- Launches ActivityWidgetSelector for widget binding and data viewing
- Non-intrusive to existing Tasker actions/conditions/events

## Usage Flow

### Current Implementation (Testing)
1. User launches app and taps "Select Widget"
2. ActivityWidgetSelector shows list of available widgets
3. User selects a widget and taps "Select Widget"
4. App attempts to bind the widget:
   - If permission already granted: widget is bound and displayed
   - If permission denied: system dialog appears asking user to grant permission to bind widget
5. After permission is granted, widget is displayed in a preview area
6. Extracted data is shown in a scrollable text view
7. User can tap "Refresh Data" to re-extract after widget updates

### Future: Tasker Integration (Not Yet Implemented)
The `WidgetNode` data will be converted to Tasker output variables:
```kotlin
@TaskerOutputObject
class WidgetDataOutput(
    val nodes: List<WidgetNodeOutput>,
    val rawData: String  // All node data as structured string
)

@TaskerOutputObject
class WidgetNodeOutput(
    val path: String,
    val value: String,
    val type: String
)
```

Actions/conditions could then:
- Query specific widget paths: `widget_data.get_node("/root/0").text`
- Get all values from a widget
- Match patterns or conditions on widget values
- Store values for use in tasks

## Important Behavior

### Defensive Design
- **Graceful failures**: Unknown view types don't crash the extractor
- **Null safety**: Missing text, IDs, or descriptions are handled as empty/null
- **Permission handling**: Binding failures are caught and reported
- **Lifecycle safety**: Resources are properly cleaned up on activity destroy

### Limitations (by design)
- **Single widget at a time**: Only one widget can be bound currently
- **No reactive updates**: Widget updates are only visible after manual refresh
- **No layout caching**: Tree is re-extracted each time
- **AppWidgetHostView only**: Works with inflated RemoteViews, not raw RemoteViews

## Testing the Implementation

1. **Build the project** (set JAVA_HOME if needed):
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install on device/emulator**:
   ```bash
   ./gradlew installDebug
   ```

3. **Open app** → Tap "Select Widget"

4. **In ActivityWidgetSelector**:
   - See list of widgets from installed apps
   - Tap a widget in the list
   - Tap "Select Widget" button
   - See the widget rendered in the preview area
   - See extracted node data in the TextView

5. **Verify extraction**:
   - Look for TextViews and their text in the output
   - Look for ImageViews and their descriptions
   - Check that resource ID names are extracted correctly
   - Verify the tree structure makes sense

## Future Enhancements

### Phase 2: Tasker Actions
- Create "Bind Widget" action for Tasker
- Create "Get Widget Data" action for Tasker
- Support multiple widgets with persistent IDs
- Query specific values by path or regex

### Phase 3: Widget Updates
- Listen for widget update broadcasts
- Cache extracted data with timestamps
- Provide delta updates to Tasker
- Support conditions: "when widget value changes to X"

### Phase 4: Value Filtering
- Allow user to select which values matter
- Create named exports (e.g., "temperature" = "/root/0/2")
- Transform values (parse numbers, dates, etc.)
- Support aggregation across widgets

## File Structure

```
app/src/main/java/com/joaomgcd/taskerpluginsample/
├── widget/
│   ├── WidgetNode.kt              (Data model)
│   ├── WidgetExtractor.kt         (Extraction logic)
│   ├── WidgetHost.kt              (Lifecycle management)
│   └── ActivityWidgetSelector.kt  (Demo UI)

app/src/main/res/layout/
├── activity_widget_selector.xml   (Demo UI layout)

app/src/main/AndroidManifest.xml   (Updated with permissions and activity)
```

## Notes

- The implementation is minimal and focused on the core POC goals
- Code is defensive but doesn't over-engineer error handling
- No comments added to code—variable/class names are self-documenting
- Ready to expand with Tasker integration when needed
- Uses Kotlin coroutines StateFlow for future async updates
