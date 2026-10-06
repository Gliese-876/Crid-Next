package cn.crid.next.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import cn.crid.next.core.ImportMode
import cn.crid.next.core.ParseResult

/** Retains the parsed import while the user changes its target semester or plan. */
internal class ImportDraftViewModel : ViewModel() {
    var initialized = false
    val busy = mutableStateOf(false)
    val busyLabel = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    val filename = mutableStateOf("")
    val result = mutableStateOf<ParseResult?>(null)
    val semesterId = mutableStateOf<String?>(null)
    val planId = mutableStateOf<String?>(null)
    val mode = mutableStateOf(ImportMode.NEW)
    val planName = mutableStateOf("")
    val onlyKnown = mutableStateOf(false)
    val acceptSemester = mutableStateOf(false)
    val success = mutableStateOf(false)
    var incomingBeingRead: String? = null
    fun clear() {
        initialized=false;busy.value=false;busyLabel.value="";error.value=null
        filename.value="";result.value=null;semesterId.value=null;planId.value=null
        mode.value=ImportMode.NEW;planName.value="";onlyKnown.value=false;acceptSemester.value=false;success.value=false
        incomingBeingRead=null
    }
}
