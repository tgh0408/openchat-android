package com.im.openchat.address.ui

import android.os.SystemClock
import androidx.compose.runtime.*
import com.im.openchat.address.data.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 列表只属于单个授权对象，各分类独立失败，不阻塞会话同步或彼此的成功结果。 */
internal class AddressBookState(val repository: AddressRepository) {
    var friends by mutableStateOf<List<DirectoryFriend>>(emptyList())
        private set
    var groups by mutableStateOf<List<DirectoryGroup>>(emptyList())
        private set
    var unread by mutableStateOf<List<ApplicationUnread>>(emptyList())
        private set
    var friendsLoading by mutableStateOf(true)
        private set
    var groupsLoading by mutableStateOf(true)
        private set
    var friendsError by mutableStateOf<String?>(null)
        private set
    var groupsError by mutableStateOf<String?>(null)
        private set
    var unreadError by mutableStateOf<String?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** 合并刷新命令，并使已打开的申请页重新读取已加载页。 */
    fun refresh() {
        revision++
        requests.trySend(Unit)
    }

    /** Confirmed removal also invalidates any list response started before the mutation. */
    fun removeFriend(uid: Long) {
        friends = friends.filterNot { it.uid == uid }
        refresh()
    }

    /** 跟随 Compose 授权效果运行；换号或撤销时所有读取子任务随父任务取消。 */
    suspend fun run() {
        refresh()
        var lastStarted = 0L
        for (ignored in requests) {
            delay((1500L - (SystemClock.elapsedRealtime() - lastStarted)).coerceAtLeast(0))
            lastStarted = SystemClock.elapsedRealtime()
            val version = revision
            coroutineScope {
                launch {
                    friendsLoading = true
                    friendsError = null
                    try {
                        val result = repository.listFriends()
                        if (version == revision) friends = result
                    }
                    catch (exception: CancellationException) { throw exception }
                    catch (exception: IOException) { if (version == revision) friendsError = exception.message ?: "联系人读取失败" }
                    finally { friendsLoading = false }
                }
                launch {
                    groupsLoading = true
                    groupsError = null
                    try {
                        val result = repository.listGroups()
                        if (version == revision) groups = result
                    }
                    catch (exception: CancellationException) { throw exception }
                    catch (exception: IOException) { if (version == revision) groupsError = exception.message ?: "群聊读取失败" }
                    finally { groupsLoading = false }
                }
                launch {
                    unreadError = null
                    try {
                        val result = repository.unreadApplications()
                        if (version == revision) unread = result
                    }
                    catch (exception: CancellationException) { throw exception }
                    catch (exception: IOException) { if (version == revision) unreadError = exception.message ?: "申请未读同步失败" }
                }
            }
        }
    }
}
