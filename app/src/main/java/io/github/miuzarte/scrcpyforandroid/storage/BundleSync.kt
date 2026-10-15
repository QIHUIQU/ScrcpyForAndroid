package io.github.miuzarte.scrcpyforandroid.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Manages a local copy of a bundle from persistent storage with debounced save.
 *
 * Usage in a ViewModel:
 * ```
 * val asBundle = BundleSyncDelegate(
 *     sharedFlow = appSettings.bundleState,
 *     save = { appSettings.saveBundle(it) },
 *     scope = viewModelScope,
 * )
 *
 * init { asBundle.start() }
 *
 * override fun onCleared() {
 *     runBlocking(Dispatchers.IO) { asBundle.flush() }
 * }
 * ```
 */
@OptIn(FlowPreview::class)
class BundleSyncDelegate<T>(
    private val sharedFlow: StateFlow<T>,
    private val save: suspend (T) -> Unit,
    private val scope: CoroutineScope,
    private val delayMs: Long = 600L,
) {
    private val _value = MutableStateFlow(sharedFlow.value)
    val value: StateFlow<T> = _value.asStateFlow()

    fun update(transform: (T) -> T) {
        _value.update(transform)
    }

    fun start() {
        scope.launch {
            sharedFlow.collectLatest { shared ->
                if (_value.value != shared) _value.value = shared
            }
        }
        scope.launch {
            _value.debounce(delayMs).collectLatest { v ->
                if (v != sharedFlow.value) save(v)
            }
        }
    }

    suspend fun flush() {
        val latest = _value.value
        if (latest != sharedFlow.value) save(latest)
    }
}
