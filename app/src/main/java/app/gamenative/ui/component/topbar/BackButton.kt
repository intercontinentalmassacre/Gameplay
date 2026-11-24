package app.gamenative.ui.component.topbar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import app.gamenative.ui.component.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        content = {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        },
    )
}

@Preview
@Composable
private fun Preview_BackButton() {
    PluviaTheme {
        Surface {
            BackButton(onClick = {})
        }
    }
}
