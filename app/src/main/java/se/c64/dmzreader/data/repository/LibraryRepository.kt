package se.c64.dmzreader.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import se.c64.dmzreader.data.db.IssueDao
import se.c64.dmzreader.data.db.IssueEntity
import se.c64.dmzreader.data.db.PublicationCount
import se.c64.dmzreader.data.db.YearCount
import se.c64.dmzreader.domain.RelatedIssue
import se.c64.dmzreader.domain.relatedIssueFor

/** Ett nummer tillsammans med det nummer det hör ihop med, om något. */
data class IssueDetails(val issue: IssueEntity, val related: RelatedIssue?)

class LibraryRepository(private val issueDao: IssueDao) {
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
