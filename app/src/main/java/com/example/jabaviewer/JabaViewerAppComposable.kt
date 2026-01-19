package com.example.jabaviewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.example.jabaviewer.ui.MainViewModel
import com.example.jabaviewer.ui.navigation.AppNavGraph
import com.example.jabaviewer.ui.theme.JabaViewerTheme

@Composable
fun JabaViewerAppContent() {
    JabaViewerTheme {
        val navController = rememberNavController()
        val mainViewModel: MainViewModel = hiltViewModel()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            AppNavGraph(
                navController = navController,
                startDestination = mainViewModel.startDestination,
            )
        }
    }
}
