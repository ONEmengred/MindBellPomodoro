package com.mindfulfocus.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mindfulfocus.R
import com.mindfulfocus.audio.EasterEggPlayer

/**
 * 藏起来的彩蛋：统计页最底下那只很小的奶蛙。
 *
 * 点它会播放一段笑声；点屏幕上别的地方、切页或者退到后台，声音就停。
 */
@Composable
fun NaiWaEasterEgg(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.alpha(0.4f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(34.dp)
                .clickable { EasterEggPlayer.play(context) }
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "不要点这个图片",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
