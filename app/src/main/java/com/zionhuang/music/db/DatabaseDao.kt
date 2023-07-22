package com.zionhuang.music.db

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.music.constants.*
import com.zionhuang.music.db.entities.*
import com.zionhuang.music.extensions.reversed
import com.zionhuang.music.extensions.toSQLiteQuery
import com.zionhuang.music.models.MediaMetadata
import com.zionhuang.music.models.toMediaMetadata
import com.zionhuang.music.ui.utils.resize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

@Dao
interface DatabaseDao {
    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY rowId")
    fun songsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary")
    fun songsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY title")
    fun songsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY totalPlayTime")
    fun songsByPlayTimeAsc(): Flow<List<Song>>

    fun songs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> songsByCreateDateAsc()
            SongSortType.NAME -> songsByNameAsc()
            SongSortType.ARTIST -> songsByRowIdAsc().map { songs ->
                songs.sortedBy { song ->
                    song.artists.joinToString(separator = "") { it.name }
                }
            }

            SongSortType.PLAY_TIME -> songsByPlayTimeAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY rowId")
    fun likedSongsByRowIdAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY inLibrary")
    fun likedSongsByCreateDateAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY title")
    fun likedSongsByNameAsc(): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM song WHERE liked ORDER BY totalPlayTime")
    fun likedSongsByPlayTimeAsc(): Flow<List<Song>>

    fun likedSongs(sortType: SongSortType, descending: Boolean) =
        when (sortType) {
            SongSortType.CREATE_DATE -> likedSongsByCreateDateAsc()
            SongSortType.NAME -> likedSongsByNameAsc()
            SongSortType.ARTIST -> likedSongsByRowIdAsc().map { songs ->
                songs.sortedBy { song ->
                    song.artists.joinToString(separator = "") { it.name }
                }
            }

            SongSortType.PLAY_TIME -> likedSongsByPlayTimeAsc()
        }.map { it.reversed(descending) }

    @Query("SELECT COUNT(1) FROM song WHERE liked")
    fun likedSongsCount(): Flow<Int>

    @Transaction
    @Query("SELECT song.* FROM song JOIN song_album_map ON song.id = song_album_map.songId WHERE song_album_map.albumId = :albumId")
    fun albumSongs(albumId: String): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position")
    fun playlistSongs(playlistId: String): Flow<List<PlaylistSong>>

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY inLibrary")
    fun artistSongsByCreateDateAsc(artistId: String): Flow<List<Song>>

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY title")
    fun artistSongsByNameAsc(artistId: String): Flow<List<Song>>

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY totalPlayTime")
    fun artistSongsByPlayTimeAsc(artistId: String): Flow<List<Song>>

    fun artistSongs(artistId: String, sortType: ArtistSongSortType, descending: Boolean) =
        when (sortType) {
            ArtistSongSortType.CREATE_DATE -> artistSongsByCreateDateAsc(artistId)
            ArtistSongSortType.NAME -> artistSongsByNameAsc(artistId)
            ArtistSongSortType.PLAY_TIME -> artistSongsByPlayTimeAsc(artistId)
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL LIMIT :previewSize")
    fun artistSongsPreview(artistId: String, previewSize: Int = 3): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT *, COUNT(1) AS referredCount
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN song ON song.id = map.relatedSongId
        WHERE songId IN (SELECT songId
                         FROM (SELECT songId
                               FROM event
                               ORDER BY ROWID DESC
                               LIMIT 5)
                         UNION
                         SELECT songId
                         FROM (SELECT songId
                               FROM event
                               WHERE timestamp > :now - 86400000 * 7
                               GROUP BY songId
                               ORDER BY SUM(playTime) DESC
                               LIMIT 5)
                         UNION
                         SELECT id
                         FROM (SELECT id
                               FROM song
                               ORDER BY totalPlayTime DESC
                               LIMIT 10))
        ORDER BY referredCount DESC
        LIMIT 100
    """
    )
    fun quickPicks(now: Long = System.currentTimeMillis()): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT *
        FROM song
        WHERE id IN (SELECT songId
                     FROM event
                     WHERE timestamp > :fromTimeStamp
                     GROUP BY songId
                     ORDER BY SUM(playTime) DESC
                     LIMIT :limit)
    """
    )
    fun mostPlayedSongs(fromTimeStamp: Long, limit: Int = 6): Flow<List<Song>>

    @Transaction
    @Query(
        """
        SELECT artist.*,
               (SELECT COUNT(1)
                FROM song_artist_map
                         JOIN song ON song_artist_map.songId = song.id
                WHERE artistId = artist.id
                  AND song.inLibrary IS NOT NULL) AS songCount
        FROM artist
                 JOIN(SELECT artistId, SUM(songTotalPlayTime) AS totalPlayTime
                      FROM song_artist_map
                               JOIN (SELECT songId, SUM(playTime) AS songTotalPlayTime
                                     FROM event
                                     WHERE timestamp > :fromTimeStamp
                                     GROUP BY songId) AS e
                                    ON song_artist_map.songId = e.songId
                      GROUP BY artistId
                      ORDER BY totalPlayTime DESC
                      LIMIT :limit)
                     ON artist.id = artistId
    """
    )
    fun mostPlayedArtists(fromTimeStamp: Long, limit: Int = 6): Flow<List<Artist>>

    @Transaction
    @Query(
        """
        SELECT albumId
        FROM song
                 JOIN (SELECT songId, SUM(playTime) AS songTotalPlayTime
                       FROM event
                       WHERE timestamp > :fromTimeStamp
                       GROUP BY songId) AS e
                      ON song.id = e.songId
        WHERE albumId IS NOT NULL
        GROUP BY albumId
        ORDER BY SUM(songTotalPlayTime) DESC
        LIMIT :limit
    """
    )
    fun mostPlayedAlbums(fromTimeStamp: Long, limit: Int = 6): Flow<List<String>>

    @Transaction
    @Query("SELECT * FROM song WHERE id = :songId")
    fun song(songId: String?): Flow<Song?>

    @Transaction
    @Query("SELECT * FROM song WHERE id IN (:songIds)")
    fun songs(songIds: List<String>): Flow<List<Song>>

    @Query("SELECT * FROM format WHERE id = :id")
    fun format(id: String?): Flow<FormatEntity?>

    @Query("SELECT * FROM lyrics WHERE id = :id")
    fun lyrics(id: String?): Flow<LyricsEntity?>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE bookmarkedAt IS NOT NULL ORDER BY bookmarkedAt")
    fun artistsByCreateDateAsc(): Flow<List<Artist>>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE bookmarkedAt IS NOT NULL ORDER BY name")
    fun artistsByNameAsc(): Flow<List<Artist>>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE bookmarkedAt IS NOT NULL ORDER BY songCount")
    fun artistsBySongCountAsc(): Flow<List<Artist>>

    fun artists(sortType: ArtistSortType, descending: Boolean) =
        when (sortType) {
            ArtistSortType.CREATE_DATE -> artistsByCreateDateAsc()
            ArtistSortType.NAME -> artistsByNameAsc()
            ArtistSortType.SONG_COUNT -> artistsBySongCountAsc()
        }.map { it.reversed(descending) }

    @Query("SELECT * FROM artist WHERE id = :id")
    fun artist(id: String): Flow<ArtistEntity?>

    @Transaction
    @Query("SELECT * FROM album ORDER BY rowId")
    fun albumsByRowIdAsc(): Flow<List<Album>>

    @Transaction
    @Query("SELECT * FROM album ORDER BY createDate")
    fun albumsByCreateDateAsc(): Flow<List<Album>>

    @Transaction
    @Query("SELECT * FROM album ORDER BY title")
    fun albumsByNameAsc(): Flow<List<Album>>

    @Transaction
    @Query("SELECT * FROM album ORDER BY year")
    fun albumsByYearAsc(): Flow<List<Album>>

    @Transaction
    @Query("SELECT * FROM album ORDER BY songCount")
    fun albumsBySongCountAsc(): Flow<List<Album>>

    @Transaction
    @Query("SELECT * FROM album ORDER BY duration")
    fun albumsByLengthAsc(): Flow<List<Album>>

    fun albums(sortType: AlbumSortType, descending: Boolean) =
        when (sortType) {
            AlbumSortType.CREATE_DATE -> albumsByCreateDateAsc()
            AlbumSortType.NAME -> albumsByNameAsc()
            AlbumSortType.ARTIST -> albumsByRowIdAsc().map { albums ->
                albums.sortedBy { album ->
                    album.artists.joinToString(separator = "") { it.name }
                }
            }

            AlbumSortType.YEAR -> albumsByYearAsc()
            AlbumSortType.SONG_COUNT -> albumsBySongCountAsc()
            AlbumSortType.LENGTH -> albumsByLengthAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT * FROM album WHERE id = :id")
    fun album(id: String): Flow<Album?>

    @Transaction
    @Query("SELECT * FROM album WHERE id = :albumId")
    fun albumWithSongs(albumId: String): Flow<AlbumWithSongs?>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist ORDER BY rowId")
    fun playlistsByCreateDateAsc(): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist ORDER BY name")
    fun playlistsByNameAsc(): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist ORDER BY songCount")
    fun playlistsBySongCountAsc(): Flow<List<Playlist>>

    fun playlists(sortType: PlaylistSortType, descending: Boolean) =
        when (sortType) {
            PlaylistSortType.CREATE_DATE -> playlistsByCreateDateAsc()
            PlaylistSortType.NAME -> playlistsByNameAsc()
            PlaylistSortType.SONG_COUNT -> playlistsBySongCountAsc()
        }.map { it.reversed(descending) }

    @Transaction
    @Query("SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist WHERE id = :playlistId")
    fun playlist(playlistId: String): Flow<Playlist?>

    @Transaction
    @Query("SELECT * FROM song WHERE title LIKE '%' || :query || '%' AND inLibrary IS NOT NULL LIMIT :previewSize")
    fun searchSongs(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(1) FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = artist.id AND song.inLibrary IS NOT NULL) AS songCount FROM artist WHERE name LIKE '%' || :query || '%'  AND bookmarkedAt IS NOT NULL LIMIT :previewSize")
    fun searchArtists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Artist>>

    @Transaction
    @Query("SELECT * FROM album WHERE title LIKE '%' || :query || '%' LIMIT :previewSize")
    fun searchAlbums(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Album>>

    @Transaction
    @Query("SELECT *, (SELECT COUNT(*) FROM playlist_song_map WHERE playlistId = playlist.id) AS songCount FROM playlist WHERE name LIKE '%' || :query || '%' LIMIT :previewSize")
    fun searchPlaylists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT * FROM event ORDER BY rowId DESC")
    fun events(): Flow<List<EventWithSong>>

    @Query("DELETE FROM event")
    fun clearListenHistory()

    @Query("SELECT * FROM search_history WHERE `query` LIKE :query || '%' ORDER BY id DESC")
    fun searchHistory(query: String = ""): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history")
    fun clearSearchHistory()

    @Query("UPDATE song SET totalPlayTime = totalPlayTime + :playTime WHERE id = :songId")
    fun incrementTotalPlayTime(songId: String, playTime: Long)

    @Query("UPDATE song SET inLibrary = :inLibrary WHERE id = :songId")
    fun inLibrary(songId: String, inLibrary: LocalDateTime?)

    @Query("SELECT COUNT(1) FROM related_song_map WHERE songId = :songId LIMIT 1")
    fun hasRelatedSongs(songId: String): Boolean

    @Query(
        """
        UPDATE playlist_song_map SET position = 
            CASE 
                WHEN position < :fromPosition THEN position + 1
                WHEN position > :fromPosition THEN position - 1
                ELSE :toPosition
            END 
        WHERE playlistId = :playlistId AND position BETWEEN MIN(:fromPosition, :toPosition) AND MAX(:fromPosition, :toPosition)
    """
    )
    fun move(playlistId: String, fromPosition: Int, toPosition: Int)

    @Query("DELETE FROM playlist_song_map WHERE playlistId = :playlistId")
    fun clearPlaylist(playlistId: String)

    @Query("SELECT * FROM artist WHERE name = :name")
    fun artistByName(name: String): ArtistEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongArtistMap)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongAlbumMap)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: AlbumArtistMap)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: PlaylistSongMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchHistory: SearchHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: Event)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: RelatedSongMap)

    @Transaction
    fun insert(mediaMetadata: MediaMetadata, block: (SongEntity) -> SongEntity = { it }) {
        if (insert(mediaMetadata.toSongEntity().let(block)) == -1L) return
        mediaMetadata.artists.forEachIndexed { index, artist ->
            val artistId = artist.id ?: artistByName(artist.name)?.id ?: ArtistEntity.generateArtistId()
            insert(
                ArtistEntity(
                    id = artistId,
                    name = artist.name
                )
            )
            insert(
                SongArtistMap(
                    songId = mediaMetadata.id,
                    artistId = artistId,
                    position = index
                )
            )
        }
    }

    @Transaction
    fun insert(albumPage: AlbumPage) {
        if (insert(AlbumEntity(
                id = albumPage.album.browseId,
                title = albumPage.album.title,
                year = albumPage.album.year,
                thumbnailUrl = albumPage.album.thumbnail,
                songCount = albumPage.songs.size,
                duration = albumPage.songs.sumOf { it.duration ?: 0 }
            )) == -1L
        ) return
        albumPage.songs.map(SongItem::toMediaMetadata)
            .onEach(::insert)
            .mapIndexed { index, song ->
                SongAlbumMap(
                    songId = song.id,
                    albumId = albumPage.album.browseId,
                    index = index
                )
            }
            .forEach(::upsert)
        albumPage.album.artists
            ?.map { artist ->
                ArtistEntity(
                    id = artist.id ?: artistByName(artist.name)?.id ?: ArtistEntity.generateArtistId(),
                    name = artist.name
                )
            }
            ?.onEach(::insert)
            ?.mapIndexed { index, artist ->
                AlbumArtistMap(
                    albumId = albumPage.album.browseId,
                    artistId = artist.id,
                    order = index
                )
            }
            ?.forEach(::insert)
    }

    @Transaction
    fun insert(albumWithSongs: AlbumWithSongs) {
        if (insert(albumWithSongs.album) == -1L) return
        albumWithSongs.songs.map(Song::toMediaMetadata).forEach(::insert)
        albumWithSongs.songs.mapIndexed { index, song ->
            SongAlbumMap(
                songId = song.id,
                albumId = albumWithSongs.album.id,
                index = index
            )
        }.forEach(::upsert)
        albumWithSongs.artists.forEach(::insert)
        albumWithSongs.artists.mapIndexed { index, artist ->
            AlbumArtistMap(
                albumId = albumWithSongs.album.id,
                artistId = artist.id,
                order = index
            )
        }.forEach(::insert)
    }

    @Update
    fun update(song: SongEntity)

    @Update
    fun update(artist: ArtistEntity)

    @Update
    fun update(playlist: PlaylistEntity)

    @Update
    fun update(map: PlaylistSongMap)

    fun update(artist: ArtistEntity, artistPage: ArtistPage) {
        update(
            artist.copy(
                name = artistPage.artist.title,
                thumbnailUrl = artistPage.artist.thumbnail.resize(544, 544),
                lastUpdateTime = LocalDateTime.now()
            )
        )
    }

    @Upsert
    fun upsert(map: SongAlbumMap)

    @Upsert
    fun upsert(lyrics: LyricsEntity)

    @Upsert
    fun upsert(format: FormatEntity)

    @Delete
    fun delete(song: SongEntity)

    @Delete
    fun delete(artist: ArtistEntity)

    @Delete
    fun delete(album: AlbumEntity)

    @Delete
    fun delete(playlist: PlaylistEntity)

    @Delete
    fun delete(playlistSongMap: PlaylistSongMap)

    @Delete
    fun delete(lyrics: LyricsEntity)

    @Delete
    fun delete(searchHistory: SearchHistory)

    @Delete
    fun delete(event: Event)

    @Query("SELECT * FROM playlist_song_map WHERE songId = :songId")
    fun playlistSongMaps(songId: String): List<PlaylistSongMap>

    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId AND position >= :from ORDER BY position")
    fun playlistSongMaps(playlistId: String, from: Int): List<PlaylistSongMap>

    @RawQuery
    fun raw(supportSQLiteQuery: SupportSQLiteQuery): Int

    fun checkpoint() {
        raw("PRAGMA wal_checkpoint(FULL)".toSQLiteQuery())
    }
}
