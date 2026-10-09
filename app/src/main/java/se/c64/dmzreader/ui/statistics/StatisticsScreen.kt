package se.c64.dmzreader.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.c64.dmzreader.data.repository.BookmarkRepository
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.data.repository.ReadingRepository
import se.c64.dmzreader.data.settings.LibraryPreferences
import se.c64.dmzreader.domain.Statistics
import se.c64.dmzreader.domain.WeekActivity
import se.c64.dmzreader.domain.YearProgress
import se.c64.dmzreader.domain.computeStatistics
import se.c64.dmzreader.domain.durationText
import se.c64.dmzreader.domain.weekLabel
import se.c64.dmzreader.domain.yearIssuesText
import se.c64.dmzreader.domain.yearPagePercent
import se.c64.dmzreader.ui.components.DataRow
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles
import java.time.ZoneId

data class StatisticsUiState(
    val loading: Boolean = true,
    val statistics: Statistics = Statistics(),
    /** Publikationerna i arkivet: id och namn. */
    val publications: List<Pair<String, String>> = emptyList(),
    val selectedPublicationId: String? = null,
) {
    /** Årgångarna i den valda publikationen. */
    val years: List<YearProgress> get() = statistics.years.filter { it.publicationId == selectedPublicationId }
}

/** Fliken Statistik (planens E12). Allt räknas fram ur användardatan; ingenting lagras. */
class StatisticsViewModel(
    libraryRepository: LibraryRepository,
    bookmarkRepository: BookmarkRepository,
    readingRepository: ReadingRepository,
    private val preferences: LibraryPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    val state: StateFlow<StatisticsUiState> = combine(
        libraryRepository.observeIssuesWithState(),
        libraryRepository.observeUserStates(),
        bookmarkRepository.observeAll(),
        readingRepository.observeSessions(),
        preferences.observeSelectedPublication(),
    ) { issues, userStates, bookmarks, sessions, selected ->
        val publications = issues.map { it.issue.publicationId to it.issue.publicationTitle }.distinct().sortedBy { it.second }
        StatisticsUiState(
            loading = false,
            statistics = computeStatistics(issues, userStates, bookmarks, sessions, now(), zone()),
            publications = publications,
            // Samma val som i biblioteket. Utan val gäller den publikation som har flest nummer.
            selectedPublicationId = selected?.takeIf { id -> publications.any { it.first == id } }
                ?: issues.groupingBy { it.issue.publicationId }.eachCount().maxByOrNull { it.value }?.key,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatisticsUiState())

    fun selectPublication(publicationId: String) {
        viewModelScope.launch { preferences.setSelectedPublication(publicationId) }
    }
}

@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    onOpenYear: (publicationId: String, year: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val statistics = state.statistics

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("statistics"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
    ) {
        item(key = "title") {
            Text("Statistik", style = MaterialTheme.typography.headlineMedium, color = DmzColors.TextPrimary)
            Spacer(Modifier.height(16.dp))
        }
        if (state.loading) return@LazyColumn

        if (statistics.isEmpty) {
            item(key = "empty") {
                Panel(modifier = Modifier.testTag("statistics-empty")) {
                    Text("Inget läst ännu", style = MaterialTheme.typography.titleMedium, color = DmzColors.TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "När du läser i ett nummer räknas sidorna och tiden här. En sida räknas som läst när den har " +
                            "visats i en och en halv sekund, och lästiden går bara medan du bläddrar eller rör skärmen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DmzColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        } else {
            item(key = "figures") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyFigure("Lästa nummer", "${statistics.issuesRead}", Modifier.weight(1f).testTag("figure-read"))
                    KeyFigure("Påbörjade", "${statistics.issuesStarted}", Modifier.weight(1f).testTag("figure-started"))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyFigure("Lästa sidor", "${statistics.pagesRead}", Modifier.weight(1f).testTag("figure-pages"))
                    KeyFigure("Lästid", durationText(statistics.readingSeconds), Modifier.weight(1f).testTag("figure-time"))
                }
                Spacer(Modifier.height(24.dp))
            }
            item(key = "activity") {
                MonoLabel("Aktivitet")
                Spacer(Modifier.height(8.dp))
                Panel(modifier = Modifier.testTag("activity")) {
                    Text("Lästid per vecka, de senaste tolv veckorna", style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    ActivityChart(statistics.activity)
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        item(key = "years-title") {
            MonoLabel("Årgångar")
            if (state.publications.size > 1) {
                PublicationPicker(state.publications, state.selectedPublicationId, viewModel::selectPublication)
            }
            Spacer(Modifier.height(8.dp))
        }
        items(state.years, key = { "y-${it.publicationId}-${it.year}" }) { year ->
            YearRow(year, onClick = { onOpenYear(year.publicationId, year.year) })
            Spacer(Modifier.height(8.dp))
        }

        if (!statistics.isEmpty) {
            item(key = "other") {
                Spacer(Modifier.height(16.dp))
                MonoLabel("Övrigt")
                Spacer(Modifier.height(8.dp))
                Panel(modifier = Modifier.testTag("statistics-other")) {
                    DataRow(
                        "Mest lästa årgång",
                        statistics.mostReadYear?.let { "${it.publicationTitle} ${it.year} · ${it.pagesRead} sidor" } ?: "–",
                    )
                    DataRow("Favoriter", "${statistics.favorites}")
                    DataRow("Bokmärken", "${statistics.bookmarks}")
                    DataRow("Olästa nummer", "${statistics.issuesUnread}")
                }
            }
        }
    }
}

/** Ett nyckeltal: stor siffra i mono, med en etikett under. Läses upp som en enhet. */
@Composable
private fun KeyFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Panel(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }) {
        Text(value, style = MonoStyles.Data.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = DmzColors.Accent)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
    }
}

/**
 * Stapeldiagram över lästiden per vecka, ritat med Canvas. Varje stapel har en egen
 * beskrivning för skärmläsare ("v. 41: 18 min").
 */
@Composable
private fun ActivityChart(weeks: List<WeekActivity>) {
    val highest = weeks.maxOfOrNull { it.seconds }?.coerceAtLeast(1L) ?: 1L
    Text(
        "Högst: ${durationText(weeks.maxOfOrNull { it.seconds } ?: 0L)}",
        style = MonoStyles.DataSmall,
        color = DmzColors.Label,
        modifier = Modifier.clearAndSetSemantics { },
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        for (week in weeks) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = "${weekLabel(week.start)}: ${durationText(week.seconds)}"
                        testTag = "week-${week.start}"
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
                    val corner = CornerRadius(3.dp.toPx())
                    // En tunn bottenlinje visar veckan även när inget är läst.
                    drawRoundRect(DmzColors.Border, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()), corner)
                    if (week.seconds > 0) {
                        val barHeight = (size.height * week.seconds / highest).coerceAtLeast(4.dp.toPx())
                        drawRoundRect(DmzColors.Accent, Offset(0f, size.height - barHeight), Size(size.width, barHeight), corner)
                    }
                }
                Text(
                    weekLabel(week.start).removePrefix("v. "),
                    style = MonoStyles.DataSmall.copy(fontSize = MonoStyles.Label.fontSize),
                    color = DmzColors.Label,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** En årgång: år, stapel för andelen lästa sidor och antal lästa nummer. Ett tryck öppnar årgångsvyn. */
@Composable
private fun YearRow(year: YearProgress, onClick: () -> Unit) {
    val percent = yearPagePercent(year)
    Panel(
        modifier = Modifier
            .clickable(role = Role.Button, onClickLabel = "Öppna årgången", onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${year.publicationTitle} ${year.year}: ${yearIssuesText(year)}, $percent procent av sidorna lästa"
            }
            .testTag("year-${year.publicationId}-${year.year}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${year.year}", style = MonoStyles.Data, color = DmzColors.TextPrimary, modifier = Modifier.width(52.dp))
            Column(modifier = Modifier.weight(1f)) {
                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    val corner = CornerRadius(size.height / 2)
                    drawRoundRect(DmzColors.Border, size = size, cornerRadius = corner)
                    if (year.pagesRead > 0) {
                        val width = (size.width * year.pageFraction).coerceAtLeast(size.height)
                        drawRoundRect(DmzColors.Accent, size = Size(width, size.height), cornerRadius = corner)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(yearIssuesText(year), style = MaterialTheme.typography.bodyMedium, color = DmzColors.TextSecondary)
            }
            Text(
                "$percent %",
                style = MonoStyles.Data,
                color = if (percent > 0) DmzColors.Accent else DmzColors.Label,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

/** Val av publikation, som i biblioteket. */
@Composable
private fun PublicationPicker(publications: List<Pair<String, String>>, selectedId: String?, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        publications.forEachIndexed { index, (id, title) ->
            SegmentedButton(
                selected = id == selectedId,
                onClick = { onSelect(id) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = publications.size),
                modifier = Modifier.heightIn(min = 48.dp),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = DmzColors.Accent,
                    activeContentColor = DmzColors.Background,
                    activeBorderColor = DmzColors.Accent,
                    inactiveContainerColor = DmzColors.Surface,
                    inactiveContentColor = DmzColors.TextSecondary,
                    inactiveBorderColor = DmzColors.BorderStrong,
                ),
                icon = {},
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
