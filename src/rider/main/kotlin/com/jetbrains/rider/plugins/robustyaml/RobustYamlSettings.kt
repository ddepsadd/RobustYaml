package com.jetbrains.rider.plugins.robustyaml

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class RobustYamlState : BaseState() {
    var autoDetect by property(true)
    var customRoots by list<String>()
}

@Service(Service.Level.PROJECT)
@State(name = "RobustYaml", storages = [Storage("robustYaml.xml")])
class RobustYamlSettings : SimplePersistentStateComponent<RobustYamlState>(RobustYamlState()) {
    companion object {
        fun getInstance(project: Project): RobustYamlSettings = project.service()
    }
}
