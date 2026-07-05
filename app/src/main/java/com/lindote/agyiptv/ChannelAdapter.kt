package com.lindote.agyiptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ChannelAdapter(
    private var channels: List<LiveStream>,
    private val onChannelClicked: (LiveStream) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivLogo: ImageView = view.findViewById(R.id.iv_channel_logo)
        val tvName: TextView = view.findViewById(R.id.tv_channel_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.tvName.text = channel.name

        // Load logo with Glide, using app_banner as fallback/placeholder
        Glide.with(holder.itemView.context)
            .load(channel.streamIcon)
            .placeholder(R.drawable.app_banner)
            .error(R.drawable.app_banner)
            .into(holder.ivLogo)

        holder.itemView.setOnClickListener {
            onChannelClicked(channel)
        }
    }

    override fun getItemCount() = channels.size

    fun updateData(newChannels: List<LiveStream>) {
        channels = newChannels
        notifyDataSetChanged()
    }
}
