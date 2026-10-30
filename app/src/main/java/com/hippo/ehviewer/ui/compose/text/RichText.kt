/*
 * Copyright 2026 EhViewer contributors
 *
 * This file is part of EhViewer
 *
 * EhViewer is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * EhViewer is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with EhViewer.
 * If not, see <https://www.gnu.org/licenses/>.
 */
package com.hippo.ehviewer.ui.compose.text

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 链接在 [AnnotatedString] 里的 tag 前缀，`toSpannable` 时据此还原 `URLSpan` */
private const val LINK_TAG_PREFIX = "link:"

/**
 * `Spanned` → `AnnotatedString`。
 *
 * 注释正文仍然走原来的 `Html.fromHtml` + `TextUrl`，这里只做展示层的类型转换，
 * 因此加粗/斜体/下划线/删除线/字号/颜色与链接语义都和旧实现一致。
 * 链接用 [LinkAnnotation.Clickable] 表达：点击由 Compose 命中，回调里交给 `UrlOpener`，
 * 不需要再从点击坐标反查 span。
 */
fun Spanned.toAnnotatedString(
    linkStyle: SpanStyle,
    baseFontSize: TextUnit = TextUnit.Unspecified,
    onLinkClick: ((String) -> Unit)? = null,
): AnnotatedString {
    val builder = AnnotatedString.Builder(toString())
    val spans = getSpans(0, length, CharacterStyle::class.java)
    for (span in spans) {
        val start = getSpanStart(span)
        val end = getSpanEnd(span)
        if (start < 0 || end <= start) continue
        when (span) {
            is URLSpan -> {
                val url = span.url
                builder.addLink(
                    LinkAnnotation.Clickable(
                        tag = LINK_TAG_PREFIX + url,
                        styles = TextLinkStyles(style = linkStyle),
                        linkInteractionListener = onLinkClick?.let { click ->
                            LinkInteractionListener { click(url) }
                        },
                    ),
                    start,
                    end,
                )
            }
            is StyleSpan -> {
                val bold = span.style and Typeface.BOLD != 0
                val italic = span.style and Typeface.ITALIC != 0
                if (bold || italic) {
                    builder.addStyle(
                        SpanStyle(
                            fontWeight = if (bold) FontWeight.Bold else null,
                            fontStyle = if (italic) FontStyle.Italic else null,
                        ),
                        start,
                        end,
                    )
                }
            }
            is UnderlineSpan -> builder.addStyle(
                SpanStyle(textDecoration = TextDecoration.Underline),
                start,
                end,
            )
            is StrikethroughSpan -> builder.addStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
                start,
                end,
            )
            is RelativeSizeSpan -> if (baseFontSize != TextUnit.Unspecified) {
                builder.addStyle(
                    SpanStyle(fontSize = (baseFontSize.value * span.sizeChange).sp),
                    start,
                    end,
                )
            }
            is ForegroundColorSpan -> builder.addStyle(
                SpanStyle(color = Color(span.foregroundColor)),
                start,
                end,
            )
        }
    }
    return builder.toAnnotatedString()
}

/**
 * `AnnotatedString` → `Spannable`，只保留 `toBBCode()` 认识的字符样式（加粗、斜体、
 * 下划线、删除线、链接）。提交评论前用它把富文本编辑器里的内容换回旧的 BBCode 管线。
 */
fun AnnotatedString.toSpannable(): SpannableString {
    val spannable = SpannableString(text)
    for (range in spanStyles) {
        val style = range.item
        val typeface = (if (style.isBold()) Typeface.BOLD else 0) or
            (if (style.fontStyle == FontStyle.Italic) Typeface.ITALIC else 0)
        if (typeface != 0) {
            spannable.setSpan(
                StyleSpan(typeface),
                range.start,
                range.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val decoration = style.textDecoration
        if (decoration != null) {
            if (TextDecoration.Underline in decoration) {
                spannable.setSpan(
                    UnderlineSpan(),
                    range.start,
                    range.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (TextDecoration.LineThrough in decoration) {
                spannable.setSpan(
                    StrikethroughSpan(),
                    range.start,
                    range.end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }
    for (range in getLinkAnnotations(0, length)) {
        val tag = (range.item as? LinkAnnotation.Clickable)?.tag ?: continue
        if (tag.startsWith(LINK_TAG_PREFIX)) {
            spannable.setSpan(
                URLSpan(tag.removePrefix(LINK_TAG_PREFIX)),
                range.start,
                range.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return spannable
}

/** 在 [start, end) 上追加样式；区间内完全相同的样式会被替换，其余样式保留 */
fun AnnotatedString.withSpanStyle(start: Int, end: Int, style: SpanStyle): AnnotatedString {
    if (start >= end) return this
    val kept = spanStyles.filterNot { it.item == style && it.start < end && it.end > start }
    return rebuild(kept + AnnotatedString.Range(style, start, end))
}

/** 清掉 [start, end) 覆盖到的字符样式与链接；落在区间外的部分会切开保留 */
fun AnnotatedString.clearFormatting(start: Int, end: Int): AnnotatedString {
    if (start >= end) return this
    return rebuild(
        spans = spanStyles.flatMap { it.cutOut(start, end) },
        links = clickableLinks().flatMap { it.cutOut(start, end) },
    )
}

/** 把 [start, end) 变成指向 [url] 的链接，旧的链接区间被替换 */
fun AnnotatedString.withLink(
    start: Int,
    end: Int,
    url: String,
    style: SpanStyle,
): AnnotatedString {
    if (start >= end) return this
    val link = LinkAnnotation.Clickable(
        tag = LINK_TAG_PREFIX + url,
        styles = TextLinkStyles(style),
        linkInteractionListener = null,
    )
    val links = clickableLinks().filterNot { it.start < end && it.end > start }
    return rebuild(
        spans = spanStyles + AnnotatedString.Range(style, start, end),
        links = links + AnnotatedString.Range(link, start, end),
    )
}

private fun AnnotatedString.clickableLinks(): List<AnnotatedString.Range<LinkAnnotation.Clickable>> = getLinkAnnotations(0, length).mapNotNull { range ->
    (range.item as? LinkAnnotation.Clickable)
        ?.let { AnnotatedString.Range(it, range.start, range.end) }
}

private fun AnnotatedString.rebuild(
    spans: List<AnnotatedString.Range<SpanStyle>> = spanStyles,
    links: List<AnnotatedString.Range<LinkAnnotation.Clickable>> = clickableLinks(),
): AnnotatedString = AnnotatedString.Builder().apply {
    append(this@rebuild.text)
    spans.forEach { addStyle(it.item, it.start, it.end) }
    links.forEach { addLink(it.item, it.start, it.end) }
}.toAnnotatedString()

private fun <T> AnnotatedString.Range<T>.cutOut(
    start: Int,
    end: Int,
): List<AnnotatedString.Range<T>> = if (this.end <= start || this.start >= end) {
    listOf(this)
} else {
    buildList {
        if (this@cutOut.start < start) add(AnnotatedString.Range(item, this@cutOut.start, start))
        if (this@cutOut.end > end) add(AnnotatedString.Range(item, end, this@cutOut.end))
    }
}

private fun SpanStyle.isBold(): Boolean {
    val weight = fontWeight ?: return false
    return weight.weight >= FontWeight.Bold.weight
}
