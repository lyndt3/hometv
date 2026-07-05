package com.lindote.agyiptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CategoryAdapter(
    private var categories: List<Category>,
    private val onCategoryFocused: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    private var selectedCategoryId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_category_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.tvName.text = category.name

        // Highlight selected category
        val isSelected = category.id == selectedCategoryId
        if (isSelected) {
            holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.primary_light))
            holder.itemView.setBackgroundResource(R.drawable.focus_bg)
        } else {
            holder.tvName.setTextColor(holder.itemView.context.getColor(R.color.white))
            holder.itemView.setBackgroundResource(R.drawable.focus_bg)
        }

        // D-pad focus listener
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (selectedCategoryId != category.id) {
                    selectedCategoryId = category.id
                    notifyDataSetChanged()
                    onCategoryFocused(category)
                }
            }
        }
    }

    override fun getItemCount() = categories.size

    fun updateData(newCategories: List<Category>) {
        categories = newCategories
        notifyDataSetChanged()
    }

    fun setSelectedCategory(categoryId: String) {
        selectedCategoryId = categoryId
        notifyDataSetChanged()
    }
}
