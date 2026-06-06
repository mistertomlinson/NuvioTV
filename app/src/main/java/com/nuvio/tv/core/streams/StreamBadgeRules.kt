package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge

data class StreamBadgeRules(
    val rules: List<StreamBadgeRule> = emptyList()
)

data class StreamBadgeRule(
    val name: String = "",
    val pattern: String = "",
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = ""
)

data class CompiledStreamBadgeFilter(
    val name: String,
    val regex: Regex?,
    val imageURL: String,
    val tagColor: String,
    val tagStyle: String,
    val textColor: String,
    val borderColor: String
)

object StreamBadgeMatcher {
    fun compile(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> =
        rules.rules.mapNotNull { rule ->
            val regex = runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            CompiledStreamBadgeFilter(
                name = rule.name, regex = regex, imageURL = rule.imageURL,
                tagColor = rule.tagColor, tagStyle = rule.tagStyle,
                textColor = rule.textColor, borderColor = rule.borderColor
            )
        }

    fun matchedBadges(stream: Stream, filters: List<CompiledStreamBadgeFilter>): List<StreamBadge> {
        if (filters.isEmpty()) return emptyList()
        val searchText = buildString {
            append(stream.name.orEmpty()).append(" ")
            append(stream.title.orEmpty()).append(" ")
            append(stream.description.orEmpty()).append(" ")
            append(stream.addonName)
        }
        return filters.mapNotNull { filter ->
            if (filter.regex?.containsMatchIn(searchText) == true) {
                StreamBadge(
                    name = filter.name, imageURL = filter.imageURL, tagColor = filter.tagColor,
                    tagStyle = filter.tagStyle, textColor = filter.textColor, borderColor = filter.borderColor
                )
            } else null
        }
    }
}
