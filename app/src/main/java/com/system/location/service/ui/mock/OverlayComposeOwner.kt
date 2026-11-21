package com.system.location.service.ui.mock

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Each overlay attachment has its own composition lifetime, without retaining an Activity. */
internal class OverlayComposeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val state = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = state.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    fun attach(view: View) {
        state.performAttach()
        state.performRestore(null)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun close() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
