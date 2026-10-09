package app.twodo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.twodo.R
import app.twodo.TwoDoApp

/**
 * First run: who you are, then either start a group for your household or join one you've been invited
 * to. No account to make, nothing to sign up for.
 */
@Composable
internal fun WelcomeScreen(app: TwoDoApp, onStart: (groupName: String) -> Unit, onJoin: () -> Unit) {
    val brand = stringResource(R.string.brand_name)
    var name by rememberSaveable { mutableStateOf(if (app.identity.hasName) app.identity.deviceName else "") }
    var groupName by rememberSaveable { mutableStateOf("Family") }
    var nameMissing by rememberSaveable { mutableStateOf(false) }

    fun withName(action: () -> Unit) {
        if (name.isBlank()) {
            nameMissing = true
            return
        }
        app.setName(name)
        action()
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        Text("Welcome to $brand", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your family's calendar and lists, shared between your phones.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "No account. No subscription. No ads.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; nameMissing = false },
            label = { Text("Your first name") },
            supportingText = { Text(if (nameMissing) "Please enter your name first" else "So the others know who added what") },
            isError = nameMissing,
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Text("Starting fresh?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text("Name your group") },
            supportingText = { Text("Your household, or anyone you plan things with") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        BigButton("Start your group", Icons.Outlined.Groups) { withName { onStart(groupName) } }
        Spacer(Modifier.height(24.dp))
        Text("Someone already invited you?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        BigButton("Join with an invite", Icons.Default.PersonAdd, outlined = true) { withName(onJoin) }
        Spacer(Modifier.height(24.dp))
        Text(
            "Everything stays on your family's phones. It's sent between them locked, so only your group can read it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
