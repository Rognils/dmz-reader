package se.c64.dmzreader.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import se.c64.dmzreader.data.db.IssueDao
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.IssueLocalStateDao
import se.c64.dmzreader.data.db.IssueUserStateDao
import se.c64.dmzreader.data.db.PublicationCount
import se.c64.dmzreader.data.db.YearCount
import se.c64.dmzreader.domain.IssueWithState
import se.c64.dmzreader.domain.RelatedIssue
import se.c64.dmzreader.domain.effectivePageCount
import se.c64.dmzreader.domain.relatedIssueFor

/** Ett nummer tillsammans med det nummer det hör ihop med, om något. */
data class IssueDetails(val issue: IssueEntity, val related: RelatedIssue?)

class LibraryRepository(
    private val issueDao: IssueDao,
    private val userStateDao: IssueUserStateDao,
    private val localStateDao: IssueLocalStateDao,
) {
    /**
     * Alla nummer i katalogen med användarens läsdata och gällande sidantal.
     * Katalogen är liten (under 200 nummer), så sammanställningen görs i minnet.
     */
    fun observeIssuesWithState(): Flow<List<IssueWithState>> =
        combine(issueDao.observeAll(), userStateDao.observeAll(), localStateDao.observeAll()) { issues, user, local ->
            val userById = user.associateBy { it.issueId }
            val localById = local.associateBy { it.issueId }
            issues.map { issue ->
                IssueWithState(
                    issue = issue,
                    state = userById[issue.issueId],
                    pageCount = effectivePageCount(issue.pageCount, localById[issue.issueId]?.observedPageCount),
                    local = localById[issue.issueId],
                )
            }
        }

    /** Numren i en årgång med läsdata, i tidningens ordning. */
    fun observeIssuesWithState(publicationId: String, year: Int): Flow<List<IssueWithState>> =
        observeIssuesWithState().map { all ->
            all.filter { it.issue.publicationId == publicationId && it.issue.year == year }
                .sortedBy { it.issue.sortKey }
        }

    /** Ett nummer med läsdata. null om numret inte finns i katalogen. */
    fun observeIssueWithState(issueId: String): Flow<IssueWithState?> =
        observeIssuesWithState().map { all -> all.firstOrNull { it.issue.issueId == issueId } }

    fun observeYearCounts(): Flow<List<YearCount>> = issueDao.observeYearCounts()
    fun observePublicationCounts(): Flow<List<PublicationCount>> = issueDao.observePublicationCounts()

    fun observeIssues(publicationId: String, year: Int): Flow<List<IssueEntity>> =
        issueDao.observeIssues(publicationId, year)

    /** Numret och dess relaterade nummer, åt båda håll. null om numret inte finns i katalogen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeIssueDetails(issueId: String): Flow<IssueDetails?> =
        issueDao.observeIssue(issueId).flatMapLatest { issue ->
            if (issue == null) {
                flowOf(null)
            } else {
                val pointedTo = issue.relatedIssueId?.let { issueDao.observeIssue(it) } ?: flowOf(null)
                combine(pointedTo, issueDao.observeIssuesRelatedTo(issue.issueId)) { target, pointingHere ->
                    IssueDetails(issue, relatedIssueFor(issue, target, pointingHere))
                }
            }
        }
}
