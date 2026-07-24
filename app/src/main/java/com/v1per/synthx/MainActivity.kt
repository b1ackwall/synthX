package com.v1per.synthx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.v1per.synthx.ui.theme.Accent
import com.v1per.synthx.ui.theme.SynthXTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynthXTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Outfit font verification — remove after confirming font loads
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "synthX",
                            style = androidx.compose.material3.MaterialTheme.typography.displayLarge,
                            color = Accent,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DefaultPreview() {
    SynthXTheme {
        Surface(modifier = Modifier.fillMaxSize()) {}
    }
}
