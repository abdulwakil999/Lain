package com.lain.assistant.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.theme.LainSalmonDeep

@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val bg = if (enabled) LainSalmon else LainMuted
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(2.dp, LainSalmonDeep, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = LainInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PixelChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) LainSalmon else Color.Transparent)
            .border(2.dp, if (selected) LainSalmonDeep else LainMuted, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) LainInk else LainCream,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
/**
 * @param isPassword hides the text *and* takes the keyboard off autopilot.
 *
 * The second half is the part that mattered and was missing. A field left on the
 * ordinary text keyboard gets the IME's sentence capitalisation and autocorrect,
 * so an API key typed or pasted into it could be stored as "Sk-or-v1-…" — and
 * because the same flag was masking the text behind dots, nobody could see it had
 * happened. Every report of a rejected key looks like this from the outside: a
 * correct key, entered correctly, silently altered on the way in.
 *
 * [KeyboardType.Password] is what turns both off, and it is set here rather than at
 * each call site so a field that hides its contents cannot be given a keyboard that
 * rewrites them.
 */
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = LainMuted) },
        singleLine = true,
        keyboardOptions = if (isPassword) {
            // Belt and braces. The password type alone stops most IMEs; the other two
            // say it outright, because "most" is how a key gets capitalised on the
            // one keyboard nobody tested.
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false
            )
        } else {
            // Ordinary prose fields keep the ordinary keyboard: the message box is
            // one of these, and taking autocorrect off somebody's typing to fix a
            // credential field would be a poor trade.
            KeyboardOptions(keyboardType = keyboardType)
        },
        visualTransformation = if (isPassword) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(6.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LainCream,
            unfocusedTextColor = LainCream,
            focusedBorderColor = LainSalmon,
            unfocusedBorderColor = LainMuted,
            cursorColor = LainSalmon
        )
    )
}
