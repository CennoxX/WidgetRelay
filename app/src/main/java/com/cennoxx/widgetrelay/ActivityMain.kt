package com.cennoxx.widgetrelay

import android.app.Activity
import android.os.Bundle
import android.widget.CompoundButton
import com.joaomgcd.taskerpluginlibrary.extensions.requestQuery
import com.cennoxx.widgetrelay.databinding.ActivityMainBinding
import com.cennoxx.widgetrelay.tasker.basic.triggerBasicTaskerEvent
import com.cennoxx.widgetrelay.tasker.playstatechanged.PlayState
import com.cennoxx.widgetrelay.tasker.playstatechanged.PlayStateChangedActivity
import com.cennoxx.widgetrelay.tasker.togglingcondition.TogglingConditionRunner


class ActivityMain : Activity() {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdgeInsets()

        binding.buttonTriggerBasicEvent.setOnClickListener { triggerBasicTaskerEvent() }
        setToggleButtonText()
        binding.buttonToggleCondition.setOnClickListener {
            TogglingConditionRunner.toggle(this)
            setToggleButtonText()
        }

        binding.radioPlaying.setOnCheckedChangeListener { radio, _ -> changePlayingState(radio, true) }
        binding.radioStopped.setOnCheckedChangeListener { radio, _ -> changePlayingState(radio, false) }
    }

    private fun setToggleButtonText() {
        binding.buttonToggleCondition.text = "Set 'Toggling' condition to ${!TogglingConditionRunner.isOn}"
    }

    fun changePlayingState(radio: CompoundButton, isPlaying: Boolean) {
        if (!radio.isChecked) return
        val artist = (binding.editTextArtist.text?.toString() ?: "Unkonwn")
        val songName = binding.editTextSong.text?.toString() ?: "Unkonwn"
        val update = PlayState(isPlaying, artist, songName)
        PlayStateChangedActivity::class.java.requestQuery(this, update)
    }
}