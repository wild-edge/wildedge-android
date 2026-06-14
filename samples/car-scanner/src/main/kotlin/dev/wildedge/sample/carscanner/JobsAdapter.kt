package dev.wildedge.sample.carscanner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class JobsAdapter(
    private val onClick: (ScanJob) -> Unit,
) : RecyclerView.Adapter<JobsAdapter.ViewHolder>() {

    private val jobs = mutableListOf<ScanJob>()

    fun submit(newJobs: List<ScanJob>) {
        jobs.clear()
        jobs.addAll(newJobs)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_scan_job, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val job = jobs[position]
        holder.thumbnail.setImageBitmap(job.thumbnail)
        holder.itemView.setOnClickListener { onClick(job) }
        when (val status = job.status) {
            is ScanJobStatus.Scanning -> {
                holder.progress.visibility = View.VISIBLE
                holder.statusText.text = "Scanning..."
            }
            is ScanJobStatus.Completed -> {
                holder.progress.visibility = View.GONE
                val result = status.results.firstOrNull()
                val candidate = result?.info?.candidates?.firstOrNull()
                holder.statusText.text = when {
                    candidate == null -> "No car found"
                    else -> listOfNotNull(candidate.brand, candidate.model).joinToString(" ")
                }
            }
            is ScanJobStatus.Failed -> {
                holder.progress.visibility = View.GONE
                holder.statusText.text = "Error"
            }
        }
    }

    override fun getItemCount(): Int = jobs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        val statusText: TextView = view.findViewById(R.id.statusText)
    }
}
