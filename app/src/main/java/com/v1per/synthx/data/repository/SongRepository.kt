package com.v1per.synthx.data.repository

interface SongRepository {
    suspend fun getSongs(): List<Song>
}
