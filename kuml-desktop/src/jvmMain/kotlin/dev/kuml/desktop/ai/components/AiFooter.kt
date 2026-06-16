package dev.kuml.desktop.ai.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun AiFooter(tokensIn: Int, tokensOut: Int, costUsd: Double, budgetUsd: Double?) {
    val overBudget = budgetUsd != null && costUsd > budgetUsd
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = "↑$tokensIn ↓$tokensOut",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "≈ \$${"%.4f".format(costUsd)}",
            style = MaterialTheme.typography.labelSmall,
            color = if (overBudget) MaterialTheme.colorScheme.error else Color.Gray,
        )
    }
}
