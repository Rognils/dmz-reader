package se.c64.dmzreader.data.offline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.c64.dmzreader.data.db.IssueLocalState
import se.c64.dmzreader.data.db.IssueLocalStateDao

/**
 * Alla ändringar av IssueLocalState går genom [update], som läser raden, ändrar
 * den och skriver tillbaka under ett lås. Läsaren och nedladdningarna skriver
 * olika fält i samma rad och får inte skriva över varandra.
 */
class LocalStateStore(private val dao: IssueLocalStateDao) {
    private val lock = Mutex()

    suspend fun get(issueId: String): IssueLocalState? = dao.get(issueId)
    suspend fun getAll(): List<IssueLocalState> = dao.getAll()
    suspend fun nextQueued(): IssueLocalState? = dao.nextQueued()
    suspend fun queuedCount(): Int = dao.queuedCount()

    /** Ändrar numrets rad. Ingenting skrivs om [transform] lämnar raden oförändrad. */
    suspend fun update(issueId: String, transform: (IssueLocalState) -> IssueLocalState): IssueLocalState =
        lock.withLock {
            val stored = dao.get(issueId)
            val current = stored ?: IssueLocalState(issueId = issueId)
            val changed = transform(current).copy(issueId = issueId)
            if (changed != current) {
                if (stored == null) dao.insert(changed) else dao.update(changed)
            }
            changed
        }
}
