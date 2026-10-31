package com.mindfulfocus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A settings row whose value can be changed either with the －/＋ buttons or by
 * typing the number directly — nobody should have to tap 70 times to go from 90 to 20.
 *
 * [onCommit] receives the raw typed text once the field loses focus or the user
 * presses "完成"; the caller is responsible for parsing and clamping.
 */
@Composable
fun NumberStepperRow(
    label: String,
    valueText: String,
    unit: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingRow(label, modifier = modifier, subtitle = unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StepButton("－", enabled = canDecrease, onClick = onDecrease)
            NumberInputField(valueText = valueText, onCommit = onCommit)
            StepButton("＋", enabled = canIncrease, onClick = onIncrease)
        }
    }
}

/** Small square －/＋ button used by [NumberStepperRow]. */
@Composable
fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit, size: Dp = 44.dp) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(size)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A compact numeric text field. Keeps its own edit buffer while focused so that
 * clamping never fights the user's typing; commits on focus loss or IME "完成".
 */
@Composable
fun NumberInputField(
    valueText: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 78.dp
) {
    var text by remember { mutableStateOf(valueText) }
    var focused by remember { mutableStateOf(false) }

    // Keep the field in sync with the stored value whenever the user is not typing.
    LaunchedEffect(valueText, focused) {
        if (!focused) text = valueText
    }

    fun commit() {
        val typed = text.trim()
        if (typed.isEmpty()) {
            text = valueText
        } else {
            onCommit(typed)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            // Digits plus a single decimal point; bounded so nothing crazy is typed.
            if (new.length <= 7 && new.count { it == '.' } <= 1 && new.all { it.isDigit() || it == '.' }) {
                text = new
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focused = false
        }),
        modifier = modifier
            .width(width)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) commit()
                focused = state.isFocused
            }
    )
}
