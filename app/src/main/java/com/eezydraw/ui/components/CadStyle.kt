package com.eezydraw.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object CadStyle {
    val chrome=Color(0xFF17202B)
    val accent=Color(0xFF65D7FF)
    val shape=RoundedCornerShape(20.dp)
}

/** Material tooltips supply both long-press discovery and accessible labels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CadTooltip(label:String,modifier:Modifier=Modifier,content:@Composable ()->Unit) {
    TooltipBox(modifier=modifier,positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip={PlainTooltip {Text(label)}},state=rememberTooltipState(),content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CadPageBar(title:String,navigation:@Composable ()->Unit,actions:@Composable RowScope.()->Unit={}) {
    TopAppBar(title={Text(title,style=MaterialTheme.typography.titleLarge)},navigationIcon=navigation,actions=actions,
        colors=TopAppBarDefaults.topAppBarColors(containerColor=CadStyle.chrome,titleContentColor=Color.White,
            navigationIconContentColor=Color.White,actionIconContentColor=Color.White))
}
