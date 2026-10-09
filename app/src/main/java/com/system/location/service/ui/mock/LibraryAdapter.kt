package com.system.location.service.ui.mock

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.system.location.service.R
import com.system.location.service.databinding.ItemLibraryBinding
import com.system.location.service.ui.viewmodel.LibraryItem
import com.system.location.service.ui.viewmodel.LibraryKind

data class LibraryRow(val kind: LibraryKind, val item: LibraryItem)

/** One adapter per view; asynchronous diffs preserve scroll and reuse unchanged rows. */
class LibraryAdapter(private val onClick: (LibraryItem) -> Unit) : ListAdapter<LibraryRow, LibraryAdapter.Holder>(DIFF) {
    class Holder(val ui: ItemLibraryBinding) : RecyclerView.ViewHolder(ui.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemLibraryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.ui.itemTitle.text = row.item.title
        holder.ui.itemDetail.text = row.item.detail
        holder.ui.itemIcon.setImageResource(when (row.kind) {
            LibraryKind.ROUTES -> R.drawable.baseline_route_24
            LibraryKind.SCENARIOS -> R.drawable.baseline_workspaces_filled_24
            LibraryKind.LOCATIONS -> R.drawable.baseline_location_on_24
        })
        holder.ui.root.setOnClickListener { onClick(row.item) }
    }
    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LibraryRow>() {
            override fun areItemsTheSame(old: LibraryRow, new: LibraryRow) = old.kind == new.kind && old.item.id == new.item.id
            override fun areContentsTheSame(old: LibraryRow, new: LibraryRow) = old == new
        }
    }
}
