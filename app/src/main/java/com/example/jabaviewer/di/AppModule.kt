package com.example.jabaviewer.di

import com.example.jabaviewer.data.crypto.CryptoEngine
import com.example.jabaviewer.data.remote.SystemTimeProvider
import com.example.jabaviewer.data.remote.TimeProvider
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

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider
}
