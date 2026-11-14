package ua.pp.yesvitlo

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class WidgetSetup : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val prefs = getSharedPreferences("esvitlo", MODE_PRIVATE)
        val places = prefs.places()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Локація віджета", style = MaterialTheme.typography.headlineSmall)
                        LazyColumn {
                            items(places, key = { it.id }) { place ->
                                TextButton(onClick = {
                                    prefs.edit().putString("widgetPlace:$id", place.id).apply()
                                    updateWidgets(this@WidgetSetup)
                                    syncBackground(this@WidgetSetup, refresh = true)
                                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }) { Text(listOfNotNull(place.name, place.queue).joinToString(" · ")) }
                            }
                        }
                    }
                }
            }
        }
    }
}
