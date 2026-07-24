package com.v1per.synthx.data.repository

class SongRepositoryImpl : SongRepository {

    override fun getSongs(): List<Song> = emptyList()

    companion object {
        val instance: SongRepository = SongRepositoryImpl()
    }
}
