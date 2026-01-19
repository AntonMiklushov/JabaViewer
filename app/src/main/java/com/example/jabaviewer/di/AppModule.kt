package com.example.jabaviewer.di

import com.example.jabaviewer.data.crypto.CryptoEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideCryptoEngine(): CryptoEngine = CryptoEngine()
}
