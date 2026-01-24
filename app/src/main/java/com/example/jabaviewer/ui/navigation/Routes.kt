package com.example.jabaviewer.ui.navigation

object Routes {
    const val Onboarding = "onboarding"
    const val Library = "library"
    const val Settings = "settings"
    const val DetailsRoute = "details/{itemId}"
    const val ReaderRoute = "reader/{itemId}"
    const val DjvuViewerRoute = "djvuViewer/{itemId}"

    fun details(itemId: String) = "details/$itemId"
    fun reader(itemId: String) = "reader/$itemId"
    fun djvuViewer(itemId: String) = "djvuViewer/$itemId"
}
