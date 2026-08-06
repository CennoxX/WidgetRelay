package com.cennoxx.widgetrelay.tasker.widgets

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.StringRes
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.applyEdgeToEdgeInsets
import com.cennoxx.widgetrelay.widget.WidgetMonitorRegistry
import com.cennoxx.widgetrelay.widget.WidgetMonitorService
import com.joaomgcd.taskerpluginlibrary.TaskerPluginConstants
import com.joaomgcd.taskerpluginlibrary.action.TaskerPluginRunnerAction
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfigHelper
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResult
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultErrorWithOutput
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultSucess

private const val ERROR_BAD_MODE = 1

const val MODE_ON = "on"
const val MODE_OFF = "off"
const val MODE_TOGGLE = "toggle"

/** The three fixed choices, in the order the config spinner shows them. */
internal val MODES = listOf(MODE_ON, MODE_OFF, MODE_TOGGLE)

/**
 * Input for the two settings actions: whether to turn the thing the action
 * names on, off, or to the opposite of what it is now.
 *
 * This is a String rather than a Boolean so Tasker performs variable
 * replacement on it - `%state` in a task is far more useful than three
 * separate hardcoded actions.
 */
@TaskerInputRoot
class MonitorSettingInput @JvmOverloads constructor(
    @field:TaskerInputField("mode", labelResIdName = "setting_mode_label") var mode: String? = MODE_ON
)

@TaskerOutputObject
class MonitorSettingOutput(
    @get:TaskerOutputVariable("widget_setting_state", labelResIdName = "widget_setting_state", htmlLabelResIdName = "widget_setting_state_description")
    var widgetSettingState: Boolean
)

/**
 * Resolves the configured mode against the current state. Accepts the values
 * the config screen writes plus the spellings a user is likely to type into a
 * variable, since the value can arrive from anywhere in a task. Returns null
 * if it means nothing.
 */
internal fun resolveMode(raw: String?, current: Boolean): Boolean? =
    when (raw?.trim()?.lowercase()) {
        MODE_ON, "true", "yes", "1", "pause", "paused" -> true
        MODE_OFF, "false", "no", "0", "resume" -> false
        MODE_TOGGLE -> !current
        else -> null
    }

// --- Pause Watching Widgets ---

/** Pauses or resumes the widget monitor, i.e. whether "Widget Updated" events fire. */
class PauseWatchingRunner : TaskerPluginRunnerAction<MonitorSettingInput, MonitorSettingOutput>() {
    override fun run(
        context: android.content.Context,
        input: TaskerInput<MonitorSettingInput>
    ): TaskerPluginResult<MonitorSettingOutput> {
        // The action names the *paused* state, but the registry stores the
        // enabled one - so "on" here means monitoring off
        val paused = resolveMode(input.regular.mode, !WidgetMonitorRegistry.isEnabled(context))
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_BAD_MODE,
                context.getString(R.string.error_bad_mode, input.regular.mode)
            )
        WidgetMonitorService.setMonitoringEnabled(context, !paused)
        return TaskerPluginResultSucess(MonitorSettingOutput(paused))
    }
}

class PauseWatchingHelper(config: TaskerPluginConfig<MonitorSettingInput>) :
    TaskerPluginConfigHelper<MonitorSettingInput, MonitorSettingOutput, PauseWatchingRunner>(config) {
    override val inputClass = MonitorSettingInput::class.java
    override val outputClass = MonitorSettingOutput::class.java
    override val runnerClass = PauseWatchingRunner::class.java
    override val addDefaultStringBlurb = false

    override fun addToStringBlurb(input: TaskerInput<MonitorSettingInput>, blurbBuilder: StringBuilder) {
        blurbBuilder.append(
            when (input.regular.mode) {
                MODE_ON -> context.getString(R.string.blurb_pause_on)
                MODE_OFF -> context.getString(R.string.blurb_pause_off)
                MODE_TOGGLE -> context.getString(R.string.blurb_pause_toggle)
                else -> context.getString(R.string.blurb_pause_variable, input.regular.mode)
            }
        )
    }
}

class ActivityConfigPauseWatching : ActivityConfigMonitorSettingBase() {
    override val titleRes = R.string.action_pause_watching
    override val descriptionRes = R.string.setting_pause_description
    override val modeLabelRes = listOf(
        R.string.mode_pause, R.string.mode_resume, R.string.mode_toggle
    )
    override val helper by lazy { PauseWatchingHelper(this) }
}

// --- Keep the CPU Awake ---

/** Turns the monitor's optional partial wake lock on or off. */
class KeepCpuAwakeRunner : TaskerPluginRunnerAction<MonitorSettingInput, MonitorSettingOutput>() {
    override fun run(
        context: android.content.Context,
        input: TaskerInput<MonitorSettingInput>
    ): TaskerPluginResult<MonitorSettingOutput> {
        val awake = resolveMode(input.regular.mode, WidgetMonitorRegistry.usesWakeLock(context))
            ?: return TaskerPluginResultErrorWithOutput(
                ERROR_BAD_MODE,
                context.getString(R.string.error_bad_mode, input.regular.mode)
            )
        WidgetMonitorService.setWakeLockEnabled(context, awake)
        return TaskerPluginResultSucess(MonitorSettingOutput(awake))
    }
}

class KeepCpuAwakeHelper(config: TaskerPluginConfig<MonitorSettingInput>) :
    TaskerPluginConfigHelper<MonitorSettingInput, MonitorSettingOutput, KeepCpuAwakeRunner>(config) {
    override val inputClass = MonitorSettingInput::class.java
    override val outputClass = MonitorSettingOutput::class.java
    override val runnerClass = KeepCpuAwakeRunner::class.java
    override val addDefaultStringBlurb = false

    override fun addToStringBlurb(input: TaskerInput<MonitorSettingInput>, blurbBuilder: StringBuilder) {
        blurbBuilder.append(
            when (input.regular.mode) {
                MODE_ON -> context.getString(R.string.blurb_wake_lock_on)
                MODE_OFF -> context.getString(R.string.blurb_wake_lock_off)
                MODE_TOGGLE -> context.getString(R.string.blurb_wake_lock_toggle)
                else -> context.getString(R.string.blurb_wake_lock_variable, input.regular.mode)
            }
        )
    }
}

class ActivityConfigKeepCpuAwake : ActivityConfigMonitorSettingBase() {
    override val titleRes = R.string.action_keep_cpu_awake
    override val descriptionRes = R.string.setting_wake_lock_description
    override val modeLabelRes = listOf(
        R.string.mode_on, R.string.mode_off, R.string.mode_toggle
    )
    override val helper by lazy { KeepCpuAwakeHelper(this) }
}

// --- Shared configuration screen ---

/**
 * Config screen for the settings actions. These don't touch a widget at all,
 * so they get a plain three-choice screen instead of the widget picker the
 * reading and clicking actions share.
 *
 * The free-text field exists because the spinner cannot express a Tasker
 * variable; anything typed there wins, and the runner resolves it at run time.
 */
abstract class ActivityConfigMonitorSettingBase : Activity(), TaskerPluginConfig<MonitorSettingInput> {
    @get:StringRes
    protected abstract val titleRes: Int

    @get:StringRes
    protected abstract val descriptionRes: Int

    /** Labels for [MODE_ON], [MODE_OFF] and [MODE_TOGGLE], in that order. */
    protected abstract val modeLabelRes: List<Int>

    protected abstract val helper: TaskerPluginConfigHelper<MonitorSettingInput, *, *>

    override val context get() = applicationContext

    private lateinit var modeSpinner: Spinner
    private lateinit var modeEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config_monitor_setting)
        applyEdgeToEdgeInsets()

        findViewById<TextView>(R.id.settingTitle).setText(titleRes)
        findViewById<TextView>(R.id.settingDescription).setText(descriptionRes)
        modeSpinner = findViewById(R.id.modeSpinner)
        modeEditText = findViewById(R.id.modeEditText)

        val labels = modeLabelRes.map { getString(it) }
        ArrayAdapter(this, R.layout.spinner_item, labels).apply {
            setDropDownViewResource(R.layout.spinner_item)
            modeSpinner.adapter = this
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { helper.finishForTasker() }

        if (intent?.getBundleExtra(TaskerPluginConstants.EXTRA_BUNDLE) != null) {
            helper.onCreate()
        }
    }

    override val inputForTasker: TaskerInput<MonitorSettingInput>
        get() {
            val custom = modeEditText.text?.toString()?.takeIf { it.isNotBlank() }
            return TaskerInput(
                MonitorSettingInput(custom ?: MODES[modeSpinner.selectedItemPosition])
            )
        }

    override fun assignFromInput(input: TaskerInput<MonitorSettingInput>) {
        val mode = input.regular.mode
        val index = MODES.indexOf(mode)
        if (index >= 0) {
            modeSpinner.setSelection(index)
            modeEditText.setText("")
        } else {
            // Not one of the fixed choices, so it must be a variable - keep it
            // instead of silently snapping back to the first option
            modeEditText.setText(mode ?: "")
        }
    }
}
