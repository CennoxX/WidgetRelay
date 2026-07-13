package com.cennoxx.widgetrelay.tasker.getip

import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputObject
import com.joaomgcd.taskerpluginlibrary.output.TaskerOutputVariable
import com.cennoxx.widgetrelay.R

@TaskerOutputObject
class GetIPOutput(
        @get:TaskerOutputVariable(VAR_IP, labelResIdName= "ip", htmlLabelResIdName = "ip_description") var publicIp: String?
){
    companion object {
        const val VAR_IP = "ip"
        const val VAR_SPLIT_IP = "split_ip"
    }
}