package com.lindote.agyiptv

import com.google.gson.annotations.SerializedName

data class Category(
    @SerializedName("category_id") val id: String,
    @SerializedName("category_name") val name: String
)

data class LiveStream(
    @SerializedName("num") val num: Int,
    @SerializedName("name") val name: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String,
    val url: String? = null,
    val tvgId: String? = null
)
