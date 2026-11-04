package com.xiao.feng.activity.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Key
import com.xiao.feng.R
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.utils.android.showToast
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Full-screen card-key gate shown before the module settings UI; the verified token is cached
 * locally so subsequent entries open directly until the server expiry.
 */
@Composable
fun LicenseGate(onVerified: () -> Unit) {
    val context = LocalContext.current
    val localizedContext = LocalXiaoFengLocalizedContext.current
    val scope = rememberCoroutineScope()
    var card by remember { mutableStateOf(ModuleLicense.savedCard()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.license_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.license_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = card,
                onValueChange = {
                    card = it
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
                label = { Text(stringResource(R.string.license_card_hint)) },
                singleLine = true,
                enabled = !checking,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    checking = true
                    error = null
                    scope.launch {
                        val result = ModuleLicense.verify(context, card)
                        checking = false
                        result.fold(
                            onSuccess = {
                                showToast(context, localizedContext.getString(R.string.license_success))
                                onVerified()
                            },
                            onFailure = {
                                error = if (it is IOException) {
                                    localizedContext.getString(R.string.license_network_error)
                                } else {
                                    localizedContext.getString(
                                        R.string.license_failed,
                                        it.message ?: localizedContext.getString(R.string.license_unknown_error),
                                    )
                                }
                            },
                        )
                    }
                },
                enabled = !checking && card.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.license_verify))
                }
            }
        }
    }
}
