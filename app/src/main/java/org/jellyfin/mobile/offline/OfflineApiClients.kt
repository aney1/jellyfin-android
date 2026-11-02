package org.jellyfin.mobile.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.sdk.api.client.ApiClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fork: offline. Api clients for the server/user a download belongs to (which may differ from the server the
 * web app currently shows), cached so a client isn't created for every progress report.
 */
class OfflineApiClients(
    private val apiClientController: ApiClientController,
    private val userDao: UserDao,
) {
    data class Target(val api: ApiClient, val userId: UUID)

    private val clients = ConcurrentHashMap<Pair<Long, Long>, Target>()

    suspend fun get(serverId: Long, userId: Long): Target? = clients[serverId to userId] ?: withContext(Dispatchers.IO) {
        val serverUser = userDao.getServerUser(serverId, userId) ?: return@withContext null
        if (serverUser.user.accessToken == null) return@withContext null
        Target(apiClientController.getApiClient(serverId, userId), serverUser.user.userId).also { target ->
            clients[serverId to userId] = target
        }
    }

    /**
     * Forget cached clients, e.g. after the user logged in again and the access token changed.
     */
    fun invalidate() = clients.clear()
}
