package com.eezydraw.ui.start

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import com.eezydraw.ui.components.CadStyle
import com.eezydraw.ui.components.CadTooltip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eezydraw.R

/** Presentation only: project ownership and navigation stay with the startup host. */
@Composable
fun StartMenuScreen(
    state: StartMenuUiState,
    onAction: (StartMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("start_menu"),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            androidx.compose.material3.Surface(color=CadStyle.chrome,contentColor=Color.White){BrandBar(onAction)}
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            if (maxWidth >= 840.dp) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 1280.dp)
                        .fillMaxSize()
                        .padding(horizontal = 40.dp, vertical = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        Introduction()
                        ProjectActions(state.canContinue, onAction)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("recent_projects_list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { RecentHeading() }
                        if (state.recentProjects.isEmpty()) {
                            item { EmptyRecentProjects() }
                        } else {
                            items(state.recentProjects, key = { it.id }) { project ->
                                RecentProjectCard(project, onAction)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("start_menu_list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item { Introduction() }
                    item { ProjectActions(state.canContinue, onAction) }
                    item { RecentHeading() }
                    if (state.recentProjects.isEmpty()) {
                        item { EmptyRecentProjects() }
                    } else {
                        items(state.recentProjects, key = { it.id }) { project ->
                            RecentProjectCard(project, onAction)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandBar(onAction: (StartMenuAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.eezy_draw_logo),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.start_menu_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB7C9D8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            CadTooltip(stringResource(R.string.settings)) { IconButton(
                onClick = { onAction(StartMenuAction.Settings) },
                modifier = Modifier.size(48.dp).testTag("settings"),
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
            }
            }
            CadTooltip(stringResource(R.string.help)) { IconButton(
                onClick = { onAction(StartMenuAction.Help) },
                modifier = Modifier.size(48.dp).testTag("help"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.help))
            }}
        }
    }
}

@Composable
private fun Introduction() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.start_menu_heading),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.start_menu_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectActions(canContinue: Boolean, onAction: (StartMenuAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            onClick = { onAction(StartMenuAction.CreateProject) },
            modifier = Modifier.fillMaxWidth().testTag("create_project"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(20.dp),
        ) {
            ActionContent(
                icon = Icons.Outlined.Add,
                title = stringResource(R.string.create_new_project),
                description = stringResource(R.string.create_project_description),
            )
        }
        OutlinedCard(
            onClick = { onAction(StartMenuAction.ContinueProject) },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth().testTag("continue_project"),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(20.dp),
        ) {
            ActionContent(
                icon = Icons.Outlined.PlayArrow,
                title = stringResource(R.string.continue_project),
                description = stringResource(R.string.continue_project_description),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallAction(
                modifier = Modifier.weight(1f).testTag("open_file"),
                icon = Icons.Outlined.FolderOpen,
                title = stringResource(R.string.open_from_file),
                onClick = { onAction(StartMenuAction.OpenFile) },
            )
            SmallAction(
                modifier = Modifier.weight(1f).testTag("templates"),
                icon = Icons.AutoMirrored.Outlined.ViewQuilt,
                title = stringResource(R.string.templates),
                onClick = { onAction(StartMenuAction.Templates) },
            )
        }
    }
}

@Composable
private fun ActionContent(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SmallAction(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RecentHeading() {
    Text(
        text = stringResource(R.string.recent_projects),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EmptyRecentProjects() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("recent_projects_empty"),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.recent_projects_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.recent_projects_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecentProjectCard(project: RecentProject, onAction: (StartMenuAction) -> Unit) {
    OutlinedCard(
        onClick = { onAction(StartMenuAction.OpenRecent(project.id)) },
        modifier = Modifier.fillMaxWidth().testTag("recent_project_${project.id}"),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectThumbnail(
                project = project,
                modifier = Modifier.size(width = 96.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = project.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
