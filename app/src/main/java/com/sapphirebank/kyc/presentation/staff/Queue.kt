package com.sapphirebank.kyc.presentation.staff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.presentation.common.ApplicationSummaryCard
import com.sapphirebank.kyc.presentation.common.EmptyState
import com.sapphirebank.kyc.presentation.common.LoadingState
import com.sapphirebank.kyc.presentation.common.SearchFilterBar
import com.sapphirebank.kyc.presentation.common.displayStatus
import com.sapphirebank.kyc.presentation.theme.Spacing

enum class SortMode(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), AMOUNT("Amount") }

class QueueFilter(val label: String, val test: (CaseFile) -> Boolean)

/** Shared list used by every role: search by ARN or customer, filter chips with counts, and sorting. */
@Composable
fun CaseQueue(
    cases: List<CaseFile>?,
    filters: List<QueueFilter>,
    onOpen: (String) -> Unit,
    emptyTitle: String,
    emptyBody: String,
    modifier: Modifier = Modifier,
    status: (CaseFile) -> Pair<String, Tone> = { it.displayStatus() },
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.(CaseFile) -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableStateOf(SortMode.NEWEST.name) }
    val sortMode = SortMode.valueOf(sort)

    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        if (header != null) item { header() }
        if (cases == null) {
            item { LoadingState("Loading applications…") }
            return@LazyColumn
        }
        val labels = filters.map { f -> "${f.label} (${cases.count(f.test)})" }
        val idx = selected.coerceIn(0, (filters.size - 1).coerceAtLeast(0))
        item {
            SearchFilterBar(
                query = query, onQuery = { query = it },
                filters = labels, selected = labels.getOrElse(idx) { "" },
                onSelect = { l -> selected = labels.indexOf(l).coerceAtLeast(0) },
                sortLabel = "Sort: ${sortMode.label}",
                onSort = { sort = SortMode.entries[(sortMode.ordinal + 1) % SortMode.entries.size].name },
            )
        }
        val q = query.trim().lowercase()
        val shown = cases.filter { filters.getOrNull(idx)?.test?.invoke(it) ?: true }
            .filter { q.isEmpty() || it.arn.lowercase().contains(q) || it.application.customerName.lowercase().contains(q) }
            .let {
                when (sortMode) {
                    SortMode.NEWEST -> it.sortedByDescending { c -> c.lastUpdated }
                    SortMode.OLDEST -> it.sortedBy { c -> c.lastUpdated }
                    SortMode.AMOUNT -> it.sortedByDescending { c -> c.application.sanctionAmountInr }
                }
            }
        if (shown.isEmpty()) {
            item {
                if (q.isNotEmpty() || cases.isNotEmpty()) EmptyState("No matching applications", "Try a different search or filter.")
                else EmptyState(emptyTitle, emptyBody)
            }
        } else {
            items(shown, key = { it.arn }) { c ->
                ApplicationSummaryCard(c, onClick = { onOpen(c.arn) }, status = status(c), footer = footer?.let { f -> { f(c) } })
            }
        }
        item { Spacer(Modifier.height(Spacing.l)) }
    }
}
