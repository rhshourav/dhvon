// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rhshourav.dhvon.data.local.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** App-lifetime scope running on the main thread (ExoPlayer must be touched on its application looper). */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ApplicationScope

/** v1 → v2: adds the per-folder "last played file" table without touching favorites, playlists or history. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `folder_last_played` (`folderPath` TEXT NOT NULL, `uri` TEXT NOT NULL, `title` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`folderPath`))")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): DhvonDatabase =
        Room.databaseBuilder(ctx, DhvonDatabase::class.java, "dhvon.db").addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration(true).build()

    @Provides fun playbackDao(db: DhvonDatabase): PlaybackDao = db.playback()
    @Provides fun favoriteDao(db: DhvonDatabase): FavoriteDao = db.favorites()
    @Provides fun playlistDao(db: DhvonDatabase): PlaylistDao = db.playlists()
    @Provides fun folderHistoryDao(db: DhvonDatabase): FolderHistoryDao = db.folderHistory()
    @Provides fun folderLastPlayedDao(db: DhvonDatabase): FolderLastPlayedDao = db.folderLastPlayed()

    @Provides @Singleton
    fun dataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { ctx.preferencesDataStoreFile("dhvon_settings") })
}
