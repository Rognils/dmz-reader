package se.c64.dmzreader.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.c64.dmzreader.data.db.IssueUserState
import se.c64.dmzreader.data.db.IssueUserStateDao
import se.c64.dmzreader.data.db.ReadStatusOverride
import se.c64.dmzreader.data.db.ReadingSession
import se.c64.dmzreader.data.db.ReadingSessionDao
import se.c64.dmzreader.domain.MIN_SESSION_SECONDS
import se.c64.dmzreader.domain.withOverride

/**
 * Användarens läsdata: läsposition, lästa sidor, manuell status och sessioner
 * (planens C4, C6 och C10).
 *
 * Alla ändringar av IssueUserState går genom [update], som läser raden, ändrar
 * den och skriver tillbaka under ett lås. Då kan läsaren och nummervyn aldrig
 * skriva över varandras fält.
 */
class ReadingRepository(
    private val userStateDao: IssueUserStateDao,
    private val sessionDao: ReadingSessionDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    suspend fun get(issueId: String): IssueUserState? = userStateDao.get(issueId)

    /** Ändrar numrets rad. Ingenting skrivs om [transform] lämnar raden oförändrad. */
    suspend fun update(issueId: String, transform: (IssueUserState) -> IssueUserState): IssueUserState =
        lock.withLock {
            val timestamp = now()
            val stored = userStateDao.get(issueId)
            val current = stored ?: IssueUserState(issueId = issueId, updatedAt = timestamp)
            val changed = transform(current)
            if (changed == current) {
                current
            } else {
                changed.copy(issueId = issueId, updatedAt = timestamp).also {
                    if (stored == null) userStateDao.insert(it) else userStateDao.update(it)
                }
            }
        }

    /** Markera som läst, som oläst, eller återställ till automatisk status (null). */
    suspend fun setOverride(issueId: String, override: ReadStatusOverride?, pageCount: Int?) {
        update(issueId) { it.withOverride(override, pageCount, now()) }
    }

    /** Favoritmarkeringen. Den uppdaterar updatedAt, som avgör vilken sida som vinner vid import av backup. */
    suspend fun setFavorite(issueId: String, favorite: Boolean) {
        update(issueId) { it.copy(isFavorite = favorite) }
    }

    /**
     * Skriver en session: en ny rad om [session] saknar id, annars en uppdatering av raden.
     * Returnerar radens id.
     */
    suspend fun saveSession(session: ReadingSession): Long {
        if (session.id == 0L) return sessionDao.insert(session)
        sessionDao.update(session)
        return session.id
    }

    suspend fun deleteSession(id: Long) {
        sessionDao.delete(id)
    }

    /** Städar bort sessioner som blev kvar med för lite aktiv tid. Körs när appen startar. */
    suspend fun removeShortSessions(): Int = sessionDao.deleteShorterThan(MIN_SESSION_SECONDS)

    fun observeSessions(): Flow<List<ReadingSession>> = sessionDao.observeAll()

}
