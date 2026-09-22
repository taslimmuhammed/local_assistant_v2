package com.local.assistant.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.ui.theme.AppColors

/**
 * Renders the Markdown subset produced by [parseMarkdown]. Safe to call with partial text, so
 * the same composable handles both a finished message and one still streaming.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(Modifier.height(gapBefore(block, blocks[index - 1])))
            }
            when (block) {
                is MarkdownBlock.Heading -> HeadingBlock(block)
                is MarkdownBlock.Paragraph -> Text(
                    text = parseInline(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppColors.TextPrimary,
                )
                is MarkdownBlock.BulletItem -> ListRow(
                    marker = "•",
                    depth = block.depth,
                    text = block.text,
                    markerWidth = 18.dp,
                )
                is MarkdownBlock.NumberedItem -> ListRow(
                    marker = "${block.label}.",
                    depth = block.depth,
                    text = block.text,
                    markerWidth = 26.dp,
                )
                is MarkdownBlock.Quote -> QuoteBlock(block.text)
                is MarkdownBlock.CodeBlock -> CodeBlockView(block.language, block.code)
                MarkdownBlock.Rule -> HorizontalDivider(
                    color = AppColors.Border,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** List items sit closer together than separate blocks do. */
private fun gapBefore(block: MarkdownBlock, previous: MarkdownBlock): androidx.compose.ui.unit.Dp {
    val bothListItems = block.isListItem && previous.isListItem
    return if (bothListItems) 3.dp else 10.dp
}

private val MarkdownBlock.isListItem: Boolean
    get() = this is MarkdownBlock.BulletItem || this is MarkdownBlock.NumberedItem

@Composable
private fun HeadingBlock(heading: MarkdownBlock.Heading) {
    val size = when (heading.level) {
        1 -> 24.sp
        2 -> 20.sp
        3 -> 17.sp
        else -> 16.sp
    }
    Text(
        text = parseInline(heading.text),
        style = TextStyle(
            fontSize = size,
            lineHeight = size * 1.35,
            fontWeight = if (heading.level <= 2) FontWeight.Bold else FontWeight.SemiBold,
        ),
        color = AppColors.TextPrimary,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

@Composable
private fun ListRow(marker: String, depth: Int, text: String, markerWidth: androidx.compose.ui.unit.Dp) {
    Row(Modifier.padding(start = (depth * 16).dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            modifier = Modifier.width(markerWidth),
        )
        Text(
            text = parseInline(text),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextPrimary,
        )
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(AppColors.Border),
        )
        Text(
            text = parseInline(text),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun CodeBlockView(language: String?, code: String) {
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AppColors.SurfaceMuted),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = language ?: "code",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(language ?: "code", code))
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(color = AppColors.Border)
        // Generated code routinely overflows a phone's width, so it scrolls rather than wraps.
        Text(
            text = code,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
            color = AppColors.TextPrimary,
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
