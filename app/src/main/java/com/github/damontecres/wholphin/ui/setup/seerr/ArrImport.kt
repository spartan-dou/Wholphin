package com.github.damontecres.wholphin.ui.setup.seerr

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.api.seerr.infrastructure.ClientException
import com.github.damontecres.wholphin.data.model.ArrType
import com.github.damontecres.wholphin.services.ArrImportResult
import com.github.damontecres.wholphin.services.ArrImportService
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.ScrollableDialog
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

sealed interface ArrImportState {
    data object Idle : ArrImportState

    data object Loading : ArrImportState

    data class Success(
        val results: List<ArrImportResult>,
    ) : ArrImportState

    data class Error(
        @param:StringRes val message: Int,
    ) : ArrImportState
}

@HiltViewModel
class ArrImportViewModel
    @Inject
    constructor(
        private val arrImportService: ArrImportService,
    ) : ViewModel() {
        val servers = arrImportService.servers

        private val _state = MutableStateFlow<ArrImportState>(ArrImportState.Idle)
        val state: StateFlow<ArrImportState> = _state

        private var importJob: Job? = null

        fun startImport() {
            importJob?.cancel()
            _state.value = ArrImportState.Loading
            importJob =
                viewModelScope.launchIO {
                    _state.value =
                        try {
                            ArrImportState.Success(arrImportService.importFromSeerr())
                        } catch (ex: CancellationException) {
                            throw ex
                        } catch (ex: ClientException) {
                            Timber.w(ex, "Seerr refused the Radarr/Sonarr settings")
                            if (ex.statusCode == 401 || ex.statusCode == 403) {
                                ArrImportState.Error(R.string.arr_import_not_admin)
                            } else {
                                ArrImportState.Error(R.string.arr_import_error)
                            }
                        } catch (ex: Exception) {
                            Timber.e(ex, "Error importing Radarr/Sonarr from Seerr")
                            ArrImportState.Error(R.string.arr_import_error)
                        }
                }
        }

        fun dismiss() {
            importJob?.cancel()
            _state.value = ArrImportState.Idle
        }
    }

@Composable
fun ArrImportDialog(
    state: ArrImportState,
    onDismissRequest: () -> Unit,
) {
    when (state) {
        ArrImportState.Idle -> {}

        ArrImportState.Loading -> {
            BasicDialog(onDismissRequest = onDismissRequest) {
                LoadingPage(Modifier.padding(16.dp))
            }
        }

        is ArrImportState.Error -> {
            ScrollableDialog(onDismissRequest = onDismissRequest) {
                item {
                    Text(
                        text = stringResource(state.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        is ArrImportState.Success -> {
            ScrollableDialog(onDismissRequest = onDismissRequest) {
                item {
                    Text(
                        text =
                            if (state.results.isEmpty()) {
                                stringResource(R.string.arr_import_none)
                            } else {
                                stringResource(R.string.arr_import_count, state.results.size)
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(state.results) { result ->
                    Column {
                        Text(
                            text =
                                when (result.server.type) {
                                    ArrType.RADARR -> "Radarr"
                                    ArrType.SONARR -> "Sonarr"
                                } + " - " + result.server.name,
                        )
                        Text(
                            text = result.server.url,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (result.reachable) R.string.arr_import_reachable else R.string.arr_import_unreachable,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (result.reachable) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                        if (!result.reachable) {
                            result.failures.forEach {
                                Text(
                                    text = "${it.url} : ${it.problem}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
