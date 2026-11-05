package io.kazusa.ehviewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

/**
 * `Settings` 的类型化包装，形制照着 EHz 的 `PrefDelegate` 来：`value` 读写同步走内存快照，
 * `valueFlow()` 给 Compose 订阅。这样从 EHz 搬过来的界面代码可以一字不改地写
 * `Settings.readingMode.value` / `Settings.readingMode.collectAsState()`。
 */
class BoolPref(val key: String, private val defValue: Boolean = false) {
    var value: Boolean
        get() = Settings.getBoolean(key, defValue)
        set(value) = Settings.putBoolean(key, value)

    fun valueFlow(): Flow<Boolean> = Settings.valueFlow(key, defValue)
}

class IntPref(val key: String, private val defValue: Int = 0) {
    var value: Int
        get() = Settings.getInt(key, defValue)
        set(value) = Settings.putInt(key, value)

    fun valueFlow(): Flow<Int> = Settings.valueFlow(key, defValue)
}

/**
 * 双向绑定的 `MutableState`：写回 `Settings`，同时跟随外部改动。
 *
 * 只在 Compose 里用；`write` 走 `Pref.value` 的 setter，`sync` 是订阅回来的单向覆盖，
 * 避免「写 → 订阅 → 再写」的环。
 */
private class PrefState<T>(
    initial: T,
    private val write: (T) -> Unit,
) : MutableState<T> {
    private val inner = mutableStateOf(initial)

    override var value: T
        get() = inner.value
        set(value) {
            if (inner.value == value) return
            inner.value = value
            write(value)
        }

    override fun component1(): T = value

    override fun component2(): (T) -> Unit = { value = it }

    fun sync(value: T) {
        if (inner.value != value) inner.value = value
    }
}

@Composable
fun BoolPref.asMutableState(): MutableState<Boolean> {
    val state = remember(this) { PrefState(value) { this.value = it } }
    LaunchedEffect(this) { valueFlow().collect { state.sync(it) } }
    return state
}

@Composable
fun IntPref.asMutableState(): MutableState<Int> {
    val state = remember(this) { PrefState(value) { this.value = it } }
    LaunchedEffect(this) { valueFlow().collect { state.sync(it) } }
    return state
}

@Composable
fun BoolPref.collectAsState(): State<Boolean> = valueFlow().collectAsState(initial = value)

@Composable
fun IntPref.collectAsState(): State<Int> = valueFlow().collectAsState(initial = value)

/** 把原始 Int 映射成枚举再交给 Compose，映射是纯函数，不必再发一次流 */
@Composable
fun <T> IntPref.collectAsState(mapper: (Int) -> T): State<T> {
    val raw = valueFlow().collectAsState(initial = value)
    return remember(this, raw) { derivedStateOf { mapper(raw.value) } }
}
