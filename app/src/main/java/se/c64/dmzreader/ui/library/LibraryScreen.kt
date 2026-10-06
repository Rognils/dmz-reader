package se.c64.dmzreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import se.c64.dmzreader.InventoryStatus
import se.c64.dmzreader.data.db.YearCount
import se.c64.dmzreader.data.inventory.InventoryLoadResult
import se.c64.dmzreader.data.repository.LibraryRepository
import se.c64.dmzreader.ui.components.MonoLabel
import se.c64.dmzreader.ui.components.Panel
import se.c64.dmzreader.ui.theme.DmzColors
import se.c64.dmzreader.ui.theme.MonoStyles

data class PublicationYears(
    val publicationId: String,
    val publicationTitle: String,
    val issueCount: Int,
    val years: List<YearCount>,
)

data class LibraryUiState(
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val publications: List<PublicationYears> = emptyList(),
) {
    val issueCount: Int get() = publications.sumOf { it.issueCount }
}

class LibraryViewModel(
    repository: LibraryRepository,
    inventoryStatus: StateFlow<InventoryStatus>,
) : ViewModel() {
    val state: StateFlow<LibraryUiState> =
        combine(repository.observeYearCounts(), inventoryStatus) { years, status ->
            val result = (status as? InventoryStatus.Done)?.result
            LibraryUiState(
                loading = status is InventoryStatus.Loading,
                errorMessage = (result as? InventoryLoadResult.Rejected)?.message,
                publications = years.groupBy { it.publicationId }.map { (id, list) ->
                    PublicationYears(id, list.first().publicationTitle, list.sumOf { it.issueCount }, list)
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
}

/**
 * FAS 1: en enkel lista över antal nummer per publikation och år.
 * Det riktiga biblioteket med omslag byggs i FAS 2.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    MonoLabel("READY.", color = DmzColors.Accent)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "DMZ Reader",
                        style = MaterialTheme.typography.displaySmall,
                        color = DmzColors.TextPrimary,
                    )
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Inställningar",
                        tint = DmzColors.TextSecondary,
                    )
                }
            }
        }

        when {
            state.errorMessage != null && state.publications.isEmpty() -> item {
                Panel {
                    Text("Arkivindexet kunde inte läsas in", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DmzColors.TextSecondary,
                    )
                }
            }

            state.loading && state.publications.isEmpty() -> item {
                Text("Läser in arkivet …", style = MaterialTheme.typography.bodyLarge, color = DmzColors.TextSecondary)
            }

            else -> {
                item {
                    Text(
                        text = "${state.issueCount} nummer i arkivet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = DmzColors.TextSecondary,
                    )
                }
                items(state.publications, key = { it.publicationId }) { publication ->
                    PublicationPanel(publication)
                }
            }
        }
    }
}

@Composable
private fun PublicationPanel(publication: PublicationYears) {
    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = publication.publicationTitle,
                style = MaterialTheme.typography.titleLarge,
                color = DmzColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text("${publication.issueCount} nummer", style = MonoStyles.DataSmall, color = DmzColors.Label)
        }
        Spacer(Modifier.height(10.dp))
        publication.years.forEachIndexed { index, year ->
            if (index > 0) HorizontalDivider(color = DmzColors.Border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = year.year.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = DmzColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(year.issueCount.toString(), style = MonoStyles.Data, color = DmzColors.TextSecondary)
            }
        }
    }
}
