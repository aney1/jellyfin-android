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
 * web app currently shows). Cached per access token, so a client isn't created for every progress report and
 * a new login is picked up right away.
 */
class OfflineApiClients(
    private val apiClientController: ApiClientController,
    private val userDao: UserDao,
) {
    data class Target(val api: ApiClient, val userId: UUID)

    private data class Key(val serverId: Long, val userId: Long, val hostname: String, val accessToken: String)

    private val clients = ConcurrentHashMap<Key, Target>()

    suspend fun get(serverId: Long, userId: Long): Target? = withContext(Dispatchers.IO) {
        val serverUser = userDao.getServerUser(serverId, userId) ?: return@withContext null
        val accessToken = serverUser.user.accessToken ?: return@withContext null
        val key = Key(serverId, userId, serverUser.server.hostname, accessToken)
        clients[key] ?: run {
            val target = Target(apiClientController.getApiClient(serverId, userId), serverUser.user.userId)
            // Drop clients with an outdated token or address of the same user
            clients.keys.removeAll { it.serverId == serverId && it.userId == userId }
            clients[key] = target
            target
        }
    }
}
