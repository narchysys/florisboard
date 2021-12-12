/*
 * Copyright (C) 2021 Patrick Goldinger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.snygg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.patrickgold.florisboard.ime.theme.FlorisImeUiSpec
import dev.patrickgold.florisboard.snygg.value.SnyggImplicitInheritValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = SnyggStylesheetSerializer::class)
class SnyggStylesheet(
    val rules: Map<SnyggRule, SnyggPropertySet>,
    val isFullyQualified: Boolean = false,
) {
    companion object {
        private const val Unspecified = Int.MIN_VALUE
        private val FallbackPropertySet = SnyggPropertySet(mapOf())

        fun getPropertySets(
            rules: Map<SnyggRule, SnyggPropertySet>,
            referenceRule: SnyggRule,
        ): List<SnyggPropertySet> {
            return rules.keys.filter { rule ->
                rule.element == referenceRule.element
                    && (rule.codes.isEmpty() || referenceRule.codes.isEmpty() || rule.codes.any { it in referenceRule.codes })
                    && (rule.groups.isEmpty() || referenceRule.groups.isEmpty() || rule.groups.any { it in referenceRule.groups })
                    && (rule.modes.isEmpty() || referenceRule.modes.isEmpty() || rule.modes.any { it in referenceRule.modes })
                    && ((referenceRule.pressedSelector == rule.pressedSelector) || !rule.pressedSelector)
                    && ((referenceRule.focusSelector == rule.focusSelector) || !rule.focusSelector)
                    && ((referenceRule.disabledSelector == rule.disabledSelector) || !rule.disabledSelector)
            }.sortedDescending().map { rules[it]!! }
        }

        fun getPropertySet(
            rules: Map<SnyggRule, SnyggPropertySet>,
            element: String,
            code: Int = Unspecified,
            group: Int = Unspecified,
            mode: Int = Unspecified,
            isPressed: Boolean = false,
            isFocus: Boolean = false,
            isDisabled: Boolean = false,
        ): SnyggPropertySet {
            val possibleRules = rules.keys.filter { it.element == element }.sortedDescending()
            possibleRules.forEach { rule ->
                val match = (code == Unspecified || rule.codes.isEmpty() || rule.codes.contains(code))
                    && (group == Unspecified || rule.groups.isEmpty() || rule.groups.contains(group))
                    && (mode == Unspecified || rule.modes.isEmpty() || rule.modes.contains(mode))
                    && (isPressed == rule.pressedSelector || !rule.pressedSelector)
                    && (isFocus == rule.focusSelector || !rule.focusSelector)
                    && (isDisabled == rule.disabledSelector || !rule.disabledSelector)
                if (match) {
                    return rules[rule]!!
                }
            }
            return rules[possibleRules.lastOrNull()] ?: FallbackPropertySet
        }
    }

    @Composable
    fun get(
        element: String,
        code: Int = Unspecified,
        group: Int = Unspecified,
        mode: Int = Unspecified,
        isPressed: Boolean = false,
        isFocus: Boolean = false,
        isDisabled: Boolean = false,
    ) = remember(element, code, group, mode, isPressed, isFocus, isDisabled) {
        getPropertySet(rules, element, code, group, mode, isPressed, isFocus, isDisabled)
    }

    fun getStatic(
        element: String,
        code: Int = Unspecified,
        group: Int = Unspecified,
        mode: Int = Unspecified,
        isPressed: Boolean = false,
        isFocus: Boolean = false,
        isDisabled: Boolean = false,
    ) = getPropertySet(rules, element, code, group, mode, isPressed, isFocus, isDisabled)

    operator fun plus(other: SnyggStylesheet): SnyggStylesheet {
        TODO()
    }

    // TODO: divide in smaller, testable sections
    fun compileToFullyQualified(stylesheetSpec: SnyggSpec): SnyggStylesheet {
        val newRules = mutableMapOf<SnyggRule, SnyggPropertySet>()
        for (rule in rules.keys.sorted()) {
            val editor = rules[rule]!!.edit()
            val propertySetSpec = stylesheetSpec.propertySetSpec(rule.element) ?: continue
            val possiblePropertySets = getPropertySets(newRules, rule)
            propertySetSpec.supportedProperties.forEach { supportedProperty ->
                if (!editor.properties.containsKey(supportedProperty.name)) {
                    val value = possiblePropertySets.firstNotNullOfOrNull {
                        it.properties[supportedProperty.name]
                    } ?: SnyggImplicitInheritValue
                    editor.properties[supportedProperty.name] = value
                }
            }
            newRules[rule] = editor.build()
        }
        val newRulesWithPressed = mutableMapOf<SnyggRule, SnyggPropertySet>()
        for ((rule, propSet) in newRules) {
            newRulesWithPressed[rule] = propSet
            if (!rule.pressedSelector) {
                val propertySetSpec = stylesheetSpec.propertySetSpec(rule.element) ?: continue
                val pressedRule = rule.copy(pressedSelector = true)
                if (!newRules.containsKey(pressedRule)) {
                    val editor = SnyggPropertySetEditor()
                    val possiblePropertySets = getPropertySets(rules, pressedRule) + getPropertySets(newRules, pressedRule)
                    propertySetSpec.supportedProperties.forEach { supportedProperty ->
                        if (!editor.properties.containsKey(supportedProperty.name)) {
                            val value = possiblePropertySets.firstNotNullOfOrNull {
                                it.properties[supportedProperty.name]
                            } ?: SnyggImplicitInheritValue
                            editor.properties[supportedProperty.name] = value
                        }
                    }
                    newRulesWithPressed[pressedRule] = editor.build()
                }
            }
        }
        return SnyggStylesheet(newRulesWithPressed, isFullyQualified = true)
    }

    fun edit(): SnyggStylesheetEditor {
        val ruleMap = rules
            .mapKeys { (rule, _) -> rule.edit() }
            .mapValues { (_, propertySet) -> propertySet.edit() }
        return SnyggStylesheetEditor(ruleMap)
    }
}

fun SnyggStylesheet(stylesheetBlock: SnyggStylesheetEditor.() -> Unit): SnyggStylesheet {
    val builder = SnyggStylesheetEditor()
    stylesheetBlock(builder)
    return builder.build()
}

class SnyggStylesheetEditor(initRules: Map<SnyggRuleEditor, SnyggPropertySetEditor>? = null){
    val rules = mutableMapOf<SnyggRuleEditor, SnyggPropertySetEditor>()

    init {
        if (initRules != null) {
            rules.putAll(initRules)
        }
    }

    operator fun String.invoke(
        codes: List<Int> = listOf(),
        groups: List<Int> = listOf(),
        modes: List<Int> = listOf(),
        pressedSelector: Boolean = false,
        focusSelector: Boolean = false,
        disabledSelector: Boolean = false,
        propertySetBlock: SnyggPropertySetEditor.() -> Unit,
    ) {
        val propertySetEditor = SnyggPropertySetEditor()
        propertySetBlock(propertySetEditor)
        val ruleEditor = SnyggRuleEditor(
            element = this,
            codes.toMutableList(),
            groups.toMutableList(),
            modes.toMutableList(),
            pressedSelector,
            focusSelector,
            disabledSelector,
        )
        rules[ruleEditor] = propertySetEditor
    }

    fun build(isFullyQualified: Boolean = false): SnyggStylesheet {
        val rulesMap = rules
            .mapKeys { (ruleEditor, _) -> ruleEditor.build() }
            .mapValues { (_, propertySetEditor) -> propertySetEditor.build() }
        return SnyggStylesheet(rulesMap, isFullyQualified)
    }
}

class SnyggStylesheetSerializer : KSerializer<SnyggStylesheet> {
    private val propertyMapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val ruleMapSerializer = MapSerializer(SnyggRule.serializer(), propertyMapSerializer)

    override val descriptor = ruleMapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: SnyggStylesheet) {
        val rawRuleMap = value.rules.mapValues { (_, propertySet) ->
            propertySet.properties.mapValues { (_, snyggValue) ->
                snyggValue.encoder().serialize(snyggValue).getOrThrow()
            }
        }
        ruleMapSerializer.serialize(encoder, rawRuleMap)
    }

    override fun deserialize(decoder: Decoder): SnyggStylesheet {
        val rawRuleMap = ruleMapSerializer.deserialize(decoder)
        val ruleMap = mutableMapOf<SnyggRule, SnyggPropertySet>()
        for ((rule, rawProperties) in rawRuleMap) {
            // FIXME: hardcoding which spec to use, the selection should happen dynamically
            val stylesheetSpec = FlorisImeUiSpec
            val propertySetSpec = stylesheetSpec.propertySetSpec(rule.element) ?: continue
            val properties = rawProperties.mapValues { (name, value) ->
                val propertySpec = propertySetSpec.propertySpec(name)
                if (propertySpec != null) {
                    for (encoder in propertySpec.encoders) {
                        encoder.deserialize(value).onSuccess { snyggValue ->
                            return@mapValues snyggValue
                        }
                    }
                }
                return@mapValues SnyggImplicitInheritValue
            }
            ruleMap[rule] = SnyggPropertySet(properties)
        }
        return SnyggStylesheet(ruleMap)
    }
}