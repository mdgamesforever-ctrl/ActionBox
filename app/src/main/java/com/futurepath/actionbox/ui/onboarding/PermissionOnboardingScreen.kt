package com.futurepath.actionbox.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.futurepath.actionbox.R

@Composable
fun PermissionOnboardingScreen(
    isAccessGranted: Boolean,
    onGrantAccessClick: () -> Unit,
    onContinueClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isAccessGranted) Icons.Filled.CheckCircle else Icons.Filled.Notifications,
            contentDescription = null,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Text(
            text = stringResource(R.string.onboarding_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
        )

        Text(
            text = stringResource(
                if (isAccessGranted) R.string.onboarding_status_granted
                else R.string.onboarding_status_not_granted
            ),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (isAccessGranted) {
            Button(onClick = onContinueClick) {
                Text(stringResource(R.string.action_continue))
            }
        } else {
            Button(onClick = onGrantAccessClick) {
                Text(stringResource(R.string.onboarding_grant_button))
            }
        }
    }
}
