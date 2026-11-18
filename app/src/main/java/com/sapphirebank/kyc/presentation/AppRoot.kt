package com.sapphirebank.kyc.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.LoadState
import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.Role
import com.sapphirebank.kyc.domain.model.RolePolicy
import com.sapphirebank.kyc.presentation.admin.AdminHome
import com.sapphirebank.kyc.presentation.admin.AdminLogs
import com.sapphirebank.kyc.presentation.common.*
import com.sapphirebank.kyc.presentation.customer.CustomerApplications
import com.sapphirebank.kyc.presentation.customer.CustomerCaseScreen
import com.sapphirebank.kyc.presentation.customer.CustomerHome
import com.sapphirebank.kyc.presentation.customer.NewApplicationScreen
import com.sapphirebank.kyc.presentation.staff.AnalystCaseScreen
import com.sapphirebank.kyc.presentation.staff.AnalystQueue
import com.sapphirebank.kyc.presentation.staff.AuditTab
import com.sapphirebank.kyc.presentation.staff.BranchCaseScreen
import com.sapphirebank.kyc.presentation.staff.BranchCases
import com.sapphirebank.kyc.presentation.staff.BranchDashboard
import com.sapphirebank.kyc.presentation.staff.BranchReports
import com.sapphirebank.kyc.presentation.staff.ComplianceCaseScreen
import com.sapphirebank.kyc.presentation.staff.ComplianceQueue
import com.sapphirebank.kyc.presentation.staff.CreditCaseScreen
import com.sapphirebank.kyc.presentation.staff.CreditQueue
import com.sapphirebank.kyc.presentation.theme.KycColors
import com.sapphirebank.kyc.presentation.theme.Sizes
import com.sapphirebank.kyc.presentation.theme.Spacing

private data class NavTab(val label: String, val icon: ImageVector)

private fun tabsFor(role: Role): List<NavTab> = when (role) {
    Role.CUSTOMER -> listOf(NavTab("Home", Icons.Filled.Home), NavTab("Applications", Icons.Filled.Description), NavTab("Profile", Icons.Filled.Person))
    Role.KYC_ANALYST -> listOf(NavTab("Queue", Icons.Filled.Assignment), NavTab("Audit", Icons.Filled.History), NavTab("Profile", Icons.Filled.Person))
    Role.CREDIT_OFFICER -> listOf(NavTab("Queue", Icons.Filled.Assignment), NavTab("Audit", Icons.Filled.History), NavTab("Profile", Icons.Filled.Person))
    Role.BRANCH_MANAGER -> listOf(NavTab("Dashboard", Icons.Filled.Dashboard), NavTab("Cases", Icons.Filled.Description), NavTab("Reports", Icons.Filled.BarChart), NavTab("Profile", Icons.Filled.Person))
    Role.COMPLIANCE_OFFICER -> listOf(NavTab("Alerts", Icons.Filled.Shield), NavTab("Audit", Icons.Filled.History), NavTab("Profile", Icons.Filled.Person))
    Role.SYSTEM_ADMIN -> listOf(NavTab("Admin", Icons.Filled.Settings), NavTab("Logs", Icons.Filled.History), NavTab("Profile", Icons.Filled.Person))
}

@Composable
fun AppRoot(container: AppContainer) {
    val load by container.load.collectAsStateWithLifecycle()
    when (val l = load) {
        LoadState.Loading -> Box(Modifier.fillMaxSize().background(KycColors.Background), contentAlignment = Alignment.Center) { LoadingState("Preparing your workspace…") }
        is LoadState.Failed -> Box(Modifier.fillMaxSize().background(KycColors.Background), contentAlignment = Alignment.Center) { ErrorState(l.message, onRetry = container::retry) }
        LoadState.Ready -> SignedFlow(container)
    }
}

@Composable
private fun SignedFlow(container: AppContainer) {
    var userId by rememberSaveable { mutableStateOf<String?>(null) }
    val actor = userId?.let { container.service.resolve(it) }
    if (actor == null) SignInScreen(container.service.directory(), onSignIn = { userId = it.userId })
    else Workspace(container, actor, onSignOut = { userId = null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Workspace(container: AppContainer, actor: Actor, onSignOut: () -> Unit) {
    val vm: WorkspaceViewModel = viewModel(key = actor.userId, factory = vmFactory { WorkspaceViewModel(container, actor) })
    val cases by vm.cases.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    var applySession by rememberSaveable { mutableIntStateOf(0) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    val tabs = remember(actor.role) { tabsFor(actor.role) }
    val snackbar = remember { SnackbarHostState() }
    val notes = remember(cases) { notificationsFor(actor, cases.orEmpty()) }

    LaunchedEffect(message?.id) {
        message?.let {
            snackbar.showSnackbar(it.text, withDismissAction = true, duration = SnackbarDuration.Long)
            vm.clearMessage()
        }
    }
    BackHandler(enabled = overlay != null) { overlay = null }

    val wide = LocalConfiguration.current.screenWidthDp >= Sizes.railBreakpoint
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sapphire Bank") },
                navigationIcon = {
                    if (overlay != null) IconButton(onClick = { overlay = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showNotes = true }) {
                        BadgedBox(badge = { if (notes.isNotEmpty()) Badge { Text(notes.size.toString()) } }) {
                            Icon(Icons.Filled.Notifications, contentDescription = "Notifications, ${notes.size} new")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KycColors.Navy, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (!wide && overlay == null) NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = KycColors.Background,
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            if (wide && overlay == null) NavigationRail {
                Spacer(Modifier.height(Spacing.s))
                tabs.forEachIndexed { i, t ->
                    NavigationRailItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) })
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = Sizes.maxContentWidth).fillMaxSize()) {
                    val o = overlay
                    when {
                        o == null -> RoleTab(actor, tab, vm, cases, onSignOut,
                            open = { overlay = "case:$it" },
                            apply = { p -> applySession++; overlay = "apply:" + (p ?: "") },
                            goTab = { tab = it })
                        o.startsWith("apply") -> NewApplicationScreen(
                            container, actor, applySession, o.removePrefix("apply:").takeIf { it.isNotBlank() && it != "apply" },
                            onClose = { overlay = null }, onOpenCase = { overlay = "case:$it" },
                        )
                        else -> {
                            val arn = o.removePrefix("case:")
                            when (actor.role) {
                                Role.CUSTOMER -> CustomerCaseScreen(vm, arn)
                                Role.KYC_ANALYST -> AnalystCaseScreen(vm, arn)
                                Role.CREDIT_OFFICER -> CreditCaseScreen(vm, arn)
                                Role.BRANCH_MANAGER -> BranchCaseScreen(vm, arn)
                                Role.COMPLIANCE_OFFICER -> ComplianceCaseScreen(vm, arn)
                                Role.SYSTEM_ADMIN -> EmptyState("No access", "System Admin cannot open individual applications.")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNotes) ModalBottomSheet(onDismissRequest = { showNotes = false }) {
        Column(Modifier.padding(horizontal = Spacing.m).padding(bottom = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ScreenTitle("Notifications")
            if (notes.isEmpty()) EmptyState("You're all caught up", "New alerts and requests will appear here.")
            notes.forEach { n ->
                Surface(color = KycColors.SurfaceMuted, shape = MaterialTheme.shapes.small) {
                    Text(n, Modifier.fillMaxWidth().padding(Spacing.m), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RoleTab(
    actor: Actor, tab: Int, vm: WorkspaceViewModel, cases: List<com.sapphirebank.kyc.domain.model.CaseFile>?,
    onSignOut: () -> Unit, open: (String) -> Unit, apply: (String?) -> Unit, goTab: (Int) -> Unit,
) {
    val profile = @Composable { ProfileScreen(actor, onSignOut) }
    when (actor.role) {
        Role.CUSTOMER -> when (tab) {
            0 -> CustomerHome(vm, cases, onApply = apply, onOpen = open, onSeeAll = { goTab(1) })
            1 -> CustomerApplications(vm, cases, onOpen = open, onApply = { apply(null) })
            else -> profile()
        }
        Role.KYC_ANALYST -> when (tab) { 0 -> AnalystQueue(vm, cases, open); 1 -> AuditTab(vm, cases); else -> profile() }
        Role.CREDIT_OFFICER -> when (tab) { 0 -> CreditQueue(vm, cases, open); 1 -> AuditTab(vm, cases); else -> profile() }
        Role.BRANCH_MANAGER -> when (tab) { 0 -> BranchDashboard(vm, cases, open, onViewAll = { goTab(1) }); 1 -> BranchCases(vm, cases, open); 2 -> BranchReports(cases); else -> profile() }
        Role.COMPLIANCE_OFFICER -> when (tab) { 0 -> ComplianceQueue(vm, cases, open); 1 -> AuditTab(vm, cases); else -> profile() }
        Role.SYSTEM_ADMIN -> when (tab) { 0 -> AdminHome(vm); 1 -> AdminLogs(vm); else -> profile() }
    }
}

@Composable
fun ProfileScreen(actor: Actor, onSignOut: () -> Unit) {
    ScreenColumn {
        ScreenTitle("Profile")
        SectionCard(null) {
            LabelValue("Name", actor.displayName)
            LabelValue("Role", actor.role.label)
            LabelValue("User ID", actor.userId)
            if (actor.delegatedLimitInr > 0) LabelValue("Approval limit", com.sapphirebank.kyc.domain.model.Inr.format(actor.delegatedLimitInr))
        }
        SectionCard("What you can do") {
            RolePolicy.allowed(actor.role).map { it.label() }.sorted().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        SectionCard("Restrictions") {
            actor.role.restrictions().forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text(
                "These rules are enforced by the service layer on every request, not only hidden in the screens.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch)) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Spacer(Modifier.width(Spacing.s))
            Text("Sign out")
        }
    }
}
