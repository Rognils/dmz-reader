package se.c64.dmzreader.data.repository

import kotlinx.coroutines.flow.Flow
import se.c64.dmzreader.data.db.IssueDao
import se.c64.dmzreader.data.db.PublicationCount
import se.c64.dmzreader.data.db.YearCount

class LibraryRepository(private val issueDao: IssueDao) {
    fun observeYearCounts(): Flow<List<YearCount>> = issueDao.observeYearCounts()
    fun observePublicationCounts(): Flow<List<PublicationCount>> = issueDao.observePublicationCounts()
}
